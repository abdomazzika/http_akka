
import akka.actor._
import akka.http.model.HttpMethods._
import akka.http.model._
import akka.stream.scaladsl._
import akka.stream.scaladsl.Flow
import play.modules.reactivemongo.json.BSONFormats
import reactivemongo.bson.BSONDocument
import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits.global
import play.api.libs.json._
import akka.http.Http
import akka.stream.FlowMaterializer
import akka.stream.scaladsl.FlowGraphImplicits._
import reactivemongo.api.collections.default.BSONCollection
import reactivemongo.api._





object Database {

  val collection = connect()


  def connect(): BSONCollection = {

    val driver = new MongoDriver
    val connection = driver.connection(List("localhost"))

    val db = connection("akka")
    db.collection("stocks")
  }

  def findAllTickers(): Future[List[BSONDocument]] = {
    val query = BSONDocument()
    val filter = BSONDocument("Company" -> 1, "Country" -> 1, "Ticker" -> 1)

    // which results in a Future[List[BSONDocument]]
    Database.collection
      .find(query, filter)
      .cursor[BSONDocument]
      .collect[List]()
  }

  def findTicker(ticker: String) : Future[Option[BSONDocument]] = {
    val query = BSONDocument("Ticker" -> ticker)

    Database.collection
      .find(query)
      .one
  }

}


/**
 * Simple Object that starts an HTTP server using akka-http. All requests are handled
 * through an Akka flow.
 */
object Boot extends App {

  // the actor system to use. Required for flowmaterializer and HTTP.
  // passed in implicit
  implicit val system = ActorSystem("Streams")
  implicit val materializer = FlowMaterializer()

  // start the server on the specified interface and port.
  val serverBinding1 = Http().bind(interface = "localhost", port = 8090)
  val serverBinding2 = Http().bind(interface = "localhost", port = 8091)

  // helper actor for some logging
  val idActor = system.actorOf(Props[IDActor],"idActor");
  idActor ! "start"

  // but we can also construct a flow from scratch and use that. For this
  // we first define some basic building blocks

  // broadcast sends the incoming event to multiple targets
  val bCast = Broadcast[HttpRequest]

  // some basic steps that each retrieve a different ticket value (as a future)
  val step1 = Flow[HttpRequest].mapAsync[String](getTickerHandler("GOOG"))
  val step2 = Flow[HttpRequest].mapAsync[String](getTickerHandler("AAPL"))
  val step3 = Flow[HttpRequest].mapAsync[String](getTickerHandler("MSFT"))

  // We'll use the source and output provided by the http endpoint
  val in = UndefinedSource[HttpRequest]
  val out = UndefinedSink[HttpResponse]

  // waits for events on the three inputs and returns a response
  val zip = ZipWith[String, String, String, HttpResponse] (
    (inp1, inp2, inp3) => new HttpResponse(status = StatusCodes.OK,entity = inp1 + inp2 + inp3)
  )

  // when an element is available on one of the inputs, take
  // that one, igore the rest
  val merge = Merge[String]
  // since merge doesn't output a HttpResponse add an additional map step.
  val mapToResponse = Flow[String].map[HttpResponse](
    (inp:String) => HttpResponse(status = StatusCodes.OK, entity = inp)
  )

  // define a flow which broadcasts the request to the three
  // steps, and uses the zipWith to combine the elements before
  val broadCastZipFlow = Flow[HttpRequest, HttpResponse]() {
    implicit builder =>

            bCast ~> step1 ~> zip.input1
      in ~> bCast ~> step2 ~> zip.input2 ~> out
            bCast ~> step3 ~> zip.input3

      (in, out)
  }

  // define another flow. This uses the merge function which
  // takes the first available response
  val broadCastMergeFlow = Flow[HttpRequest, HttpResponse]() {
    implicit builder =>

            bCast ~> step1 ~> merge
      in ~> bCast ~> step2 ~> merge ~> mapToResponse ~> out
            bCast ~> step3 ~> merge

      (in, out)
  }

  // Handles port 8090
  serverBinding1.connections.foreach { connection =>
    connection.handleWith(broadCastMergeFlow)
//    idActor ! "start"
  }

  // Handles port 8091
  serverBinding2.connections.foreach { connection =>
    connection.handleWith(Flow[HttpRequest].mapAsync(asyncHandler))
//    idActor ! "start"
  }

  def getTickerHandler(tickName: String)(request: HttpRequest): Future[String] = {
    // query the database
    val ticker = Database.findTicker(tickName)

    Thread.sleep(Math.random() * 1000 toInt)

    // use a simple for comprehension, to make
    // working with futures easier.
    for {
      t <- ticker
    } yield  {
      t match {
        case Some(bson) => convertToString(bson)
        case None => ""
      }
    }
  }

  // With an async handler, we use futures. Threads aren't blocked.
  def asyncHandler(request: HttpRequest): Future[HttpResponse] = {

    // we match the request, and some simple path checking
    request match {

      // match specific path. Returns all the avaiable tickers
      case HttpRequest(GET, Uri.Path("/getAllTickers"), _, _, _) => {

        // make a db call, which returns a future.
        // use for comprehension to flatmap this into
        // a Future[HttpResponse]
        for {
          input <- Database.findAllTickers
        } yield {
          HttpResponse(entity = convertToString(input))
        }
      }

      // match GET pat. Return a single ticker
      case HttpRequest(GET, Uri.Path("/get"), _, _, _) => {

        // next we match on the query paramter
        request.uri.query.get("ticker") match {

            // if we find the query parameter
            case Some(queryParameter) => {

              // query the database
              val ticker = Database.findTicker(queryParameter)

              // use a simple for comprehension, to make
              // working with futures easier.
              for {
                t <- ticker
              } yield  {
                t match {
                  case Some(bson) => HttpResponse(entity = convertToString(bson))
                  case None => HttpResponse(status = StatusCodes.OK)
                }
              }
            }

            // if the query parameter isn't there
            case None => Future(HttpResponse(status = StatusCodes.OK))
          }
      }

      // Simple case that matches everything, just return a not found
      case HttpRequest(_, _, _, _, _) => {
        Future[HttpResponse] {
          HttpResponse(status = StatusCodes.NotFound)
        }
      }
    }
  }


  def convertToString(input: List[BSONDocument]) : String = {
    input
      .map(f => convertToString(f))
      .mkString("[", ",", "]")
  }

  def convertToString(input: BSONDocument) : String = {
    Json.stringify(BSONFormats.toJSON(input))
  }
}


class IDActor extends Actor with ActorLogging {

 def receive = {
    case "start" =>
      log.info("Current Actors in system:")
      self ! ActorPath.fromString("akka://Streams/user/")

    case path: ActorPath =>
      context.actorSelection(path / "*") ! Identify(())

    case ActorIdentity(_, Some(ref)) =>
      log.info(ref.toString())
      self ! ref.path


  }
}