/*
 * Copyright (c) 2016 Snowplow Analytics Ltd. All rights reserved.
 *
 * This program is licensed to you under the Apache License Version 2.0,
 * and you may not use this file except in compliance with the Apache License Version 2.0.
 * You may obtain a copy of the Apache License Version 2.0 at http://www.apache.org/licenses/LICENSE-2.0.
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the Apache License Version 2.0 is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the Apache License Version 2.0 for the specific language governing permissions and limitations there under.
 */
package com.snowplowanalytics.iglu.ctl

// cats
import cats.data._
import cats.instances.stream._
import cats.syntax.either._

// scalaj-http
import scalaj.http.{ Http, HttpRequest, HttpResponse }

// json4s
import org.json4s.DefaultFormats
import org.json4s.jackson.JsonMethods.parse

// Java
import java.io.File
import java.net.URI
import java.util.UUID

// Iglu core
import com.snowplowanalytics.iglu.core.json4s.implicits._

// Schema DDL
import com.snowplowanalytics.iglu.schemaddl.IgluSchema

// This project
import FileUtils._
import PushCommand._

/**
 * Class holding arguments passed from shell into `sync` igluctl command
 * and command's main logic
 *
 * @param registryRoot full URL (host, port) of Iglu Registry
 * @param masterApiKey mater key UUID which can be used to create any Schema
 * @param inputDir directory with JSON Schemas or single JSON file
 */
case class PushCommand(registryRoot: HttpUrl, masterApiKey: UUID, inputDir: File, isPublic: Boolean)
  extends Command.CtlCommand with IgluctlConfig.IgluctlAction {

  /**
   * Primary function, performing IO reading, processing and printing results
   */
  def process(): Unit = {
    val apiKeys = getApiKeys(buildCreateKeysRequest)
    val jsons = getJsonFilesStream(inputDir, Some(filterJsonSchemas))

    val resultsT = for {    // disjunctions nested into list
      request  <- EitherT(buildRequests(apiKeys, jsons))
      response <- EitherT.fromEither(postSchema(request))
    } yield response
    val results = resultsT.value.map(flattenResult)

    // Sink results-stream into end-of-the-app
    val total = results.foldLeft(Total.empty)((total, report) => total.add(report))
    total.clean { () => apiKeys match {
      case Right(keys) =>
        deleteKey(keys.read, "Read")
        deleteKey(keys.write, "Write")
      case _ => println("INFO: No keys were created")
    }}
    total.exit()
  }

  /**
   * Build HTTP POST-request with master apikey to create temporary
   * read/write apikeys
   *
   * @return HTTP POST-request ready to be sent
   */
  def buildCreateKeysRequest: HttpRequest =
    Http(s"$registryRoot/api/auth/keygen")
      .header("apikey", masterApiKey.toString)
      .postForm(List(("vendor_prefix", "*")))

  /**
   * Build stream of HTTP requests with auth and Schema as POST data
   * In case of failed `apiKey` - this is one-element (error) Stream
   *
   * @param apiKeys pair of read/write apikeys, possibly containing error
   * @param jsons stream of Json files, each of which can contain some error
   *              (parsing, non-self-describing, etc)
   * @return lazy stream of requests ready to be sent
   */
  def buildRequests(apiKeys: Either[String, ApiKeys], jsons: JsonStream): RequestStream = {
    val resultsT = for {
      writeKey <- EitherT.fromEither(apiKeys.map(_.write))
      json     <- EitherT(jsons)
      schema   <- EitherT.fromEither(Utils.extractSchema(json))
    } yield buildRequest(schema, writeKey)
    resultsT.value
  }

  /**
   * Build HTTP POST-request with JSON Schema and authenticated with temporary
   * write key
   *
   * @param schema valid self-describing JSON Schema
   * @param writeKey temporary apikey allowed to write any Schema
   * @return HTTP POST-request ready to be sent
   */
  def buildRequest(schema: IgluSchema, writeKey: String): HttpRequest =
    Http(s"$registryRoot/api/schemas/${schema.self.schemaKey.toPath}")
      .header("apikey", writeKey)
      .param("isPublic", isPublic.toString)
      .put(schema.asString)

  /**
   * Send DELETE request for temporary key.
   * Performs IO
   *
   * @param key UUID of temporary key
   * @param purpose what exact key being deleted, used to log, can be empty
   */
  def deleteKey(key: String, purpose: String): Unit = {
    val request = Http(s"$registryRoot/api/auth/keygen")
      .header("apikey", masterApiKey.toString)
      .param("key", key)
      .method("DELETE")

    Validated.catchNonFatal(request.asString) match {
      case Validated.Valid(response) if response.isSuccess => println(s"$purpose key $key deleted")
      case Validated.Valid(response) => println(s"FAILURE: DELETE $purpose $key response: ${response.body}")
      case Validated.Invalid(throwable) => println(s"FAILURE: $purpose $key: ${throwable.toString}")
    }
  }

  /**
   * End-of-the-world data containing all results of uploading and
   * app closing logic
   */
  case class Total(updates: Int, creates: Int, failures: Int, unknown: Int) {
    /**
     * Print summary information and exit with 0 or 1 status depending on
     * presence of errors during processing
     */
    def exit(): Unit = {
      println(s"TOTAL: ${creates + updates} Schemas successfully uploaded ($creates created; $updates updated)")
      println(s"TOTAL: $failures failed Schema uploads")
      if (unknown > 0) println(s"WARNING: $unknown unknown statuses")

      if (unknown > 0 || failures > 0) sys.exit(1)
      else ()
    }

    /**
     * Modify end-of-the-world object, by sinking reports and printing info
     * Performs IO
     *
     * @param result result of upload
     * @return new modified total object
     */
    def add(result: Result): Total = result match {
      case s @ Result(_, Updated) =>
        println(s"SUCCESS: ${s.asString}")
        copy(updates = updates + 1)
      case s @ Result(_, Created) =>
        println(s"SUCCESS: ${s.asString}")
        copy(creates = creates + 1)
      case s @ Result(_, Failed) =>
        println(s"FAILURE: ${s.asString}")
        copy(failures = failures + 1)
      case s @ Result(_, Unknown) =>
        println(s"FAILURE: ${s.asString}")
        copy(unknown = unknown + 1)
    }

    /**
     * Perform cleaning
 *
     * @param f cleaning function
     */
    def clean(f: () => Unit): Unit = f()
  }
  
  object Total {
    val empty = Total(0,0,0,0)
  }
}

/**
 * Companion objects, containing functions not closed on `masterApiKey`, `registryRoot`, etc
 */
object PushCommand {

  /**
   * Anything that can bear error message
   */
  type Failing[A] = Either[String, A]

  /**
   * Lazy stream of JSON files, containing possible error, file info and valid JSON
   */
  type JsonStream = Stream[Failing[JsonFile]]

  /**
   * Lazy stream of HTTP requests ready to be sent, which also can be errors
   */
  type RequestStream = Stream[Failing[HttpRequest]]

  // json4s serialization
  private implicit val formats = DefaultFormats

  /**
   * Class container holding temporary read/write apikeys, extracted from
   * server response using `getApiKey`
   *
   * @param read stringified UUID for read apikey (not used anywhere)
   * @param write stringified UUID for write apikey (not used anywhere)
   */
  case class ApiKeys(read: String, write: String)

  /**
   * Common server message extracted from HTTP JSON response
   *
   * @param status HTTP status code
   * @param message human-readable message
   * @param location optional URI available for successful upload
   */
  case class ServerMessage(status: Int, message: String, location: Option[String])
  object ServerMessage {
    def asString(status: Int, message: String, location: Option[String]): String =
      s"$message ${location.map("at " + _ + " ").getOrElse("")} ($status)"
  }

  /**
   * ADT representing all possible statuses for Schema upload
   */
  sealed trait Status extends Serializable
  case object Updated extends Status
  case object Created extends Status
  case object Unknown extends Status
  case object Failed extends Status

  /**
   * Final result of uploading schema, with server response or error message
   *
   * @param serverMessage message, represented as valid [[ServerMessage]]
   *                      extracted from response or plain string if response
   *                      was unexpected
   * @param status short description of outcome
   */
  case class Result(serverMessage: Either[String, ServerMessage], status: Status) {
    def asString: String =
      serverMessage match {
        case Right(message) => ServerMessage.asString(message.status, message.message, message.location)
        case Left(responseBody) => responseBody
      }
  }

  /**
   * Type-tag used to mark HTTP request as aiming to create apikeys
   */
  sealed trait CreateKeys

  /**
   * Type-tag used to mark HTTP request as aiming to post JSON Schema
   */
  sealed trait PostSchema

  /**
   * Type-tag used to mark URL as HTTP
   */
  sealed trait HttpUrlTag

  type HttpUrl = URI

  /**
   * Transform failing [[Result]] to plain [[Result]] by inserting exception
   * message instead of server message
   *
   * @param result disjucntion of string with result
   * @return plain result
   */
  def flattenResult(result: Failing[Result]): Result =
    result match {
      case Right(status) => status
      case Left(failure) => Result(Left(failure), Failed)
    }

  /**
   * Extract stringified message from server response through [[ServerMessage]]
   *
   * @param response HTTP response from Iglu registry, presumably containing JSON
   * @return success message processed from JSON or error message if upload
   *         wasn't successful
   */
  def getUploadStatus(response: HttpResponse[String]): Result = {
    if (response.isSuccess)
      Either.catchNonFatal(parse(response.body).extract[ServerMessage]) match {
        case Right(serverMessage) if serverMessage.message.contains("updated") =>
          Result(Right(serverMessage), Updated)
        case Right(serverMessage) =>
          Result(Right(serverMessage), Created)
        case Left(_) =>
          Result(Left(response.body), Unknown)
      }
    else {
      Result(Left(response.body), Failed)
    }
  }

  /**
   * Perform HTTP request bundled with master apikey to create and get
   * temporary read/write apikeys.
   * Performs IO
   *
   * @param request HTTP request to /api/auth/keygen authenticated by master
   *                apikey (tagged with [[CreateKeys]])
   * @return pair of apikeys for successful creation and extraction
   *         error message otherwise
   */
  def getApiKeys(request: HttpRequest): Failing[ApiKeys] = {
    val apiKeys = for {
      response  <- Either.catchNonFatal(request.asString)
      json      <- Either.catchNonFatal(parse(response.body))
      extracted <- Either.catchNonFatal(json.extract[ApiKeys])
    } yield extracted

    apiKeys.leftMap(e => cutString(e.toString))
  }

  /**
   * Perform HTTP request bundled with temporary write key and valid
   * self-describing JSON Schema to /api/schemas/SCHEMAPATH to publish new
   * Schema.
   * Performs IO
   *
   * @param request HTTP POST-request with JSON Schema (tagged with [[PostSchema]])
   * @return successful parsed message or error message
   */
  def postSchema(request: HttpRequest): Failing[Result] =
    for {
      response <- Either.catchNonFatal(request.asString).leftMap(_.toString)
    } yield getUploadStatus(response)

  /**
   * Cut possibly long string (as compressed HTML) to a string with three dots
   */
  private def cutString(s: String, length: Short = 256): String = {
    val origin = s.take(length)
    if (origin.length == length) origin + "..."
    else origin
  }

  /**
   * Parse registry root (HTTP URL) from string with default `http://` protocol
   *
   * @param url string representing just host or full URL of registry root.
   *            Registry root is URL **without** /api
   * @return either error or URL tagged as HTTP in case of success
   */
  def parseRegistryRoot(url: String): Either[Throwable, HttpUrl] =
    Either.catchNonFatal {
      if (url.startsWith("http://") || url.startsWith("https://")) {
        new URI(url.stripSuffix("/"))
      } else {
        new URI("http://" + url.stripSuffix("/"))
      }
    }
}
