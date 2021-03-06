package typedapi.server

import cats.Monad
import cats.implicits._
import cats.effect.IO
import org.http4s._
import org.http4s.dsl._
import org.http4s.server.Server
import org.http4s.server.blaze.BlazeBuilder
import shapeless._
import shapeless.ops.hlist.Prepend

import scala.language.higherKinds

package object http4s {

  implicit def noReqBodyExecutor[El <: HList, In <: HList, CIn <: HList, F[_]: Monad, FOut](implicit encoder: EntityEncoder[F, FOut]) = new NoReqBodyExecutor[El, In, CIn, F, FOut] {
    type R   = Request[F]
    type Out = F[Response[F]]

    private val dsl = Http4sDsl[F]
    import dsl._

    def apply(req: R, eReq: EndpointRequest, endpoint: Endpoint[El, In, CIn, CIn, F, FOut]): Either[ExtractionError, Out] = {
      extract(eReq, endpoint).map { extracted =>
        Monad[F].flatMap(execute(extracted, endpoint)) { response =>
          val resp: F[Response[F]] = Ok.apply(response)(Monad[F], encoder)

          resp
        }
      }
    }
  }

  implicit def withReqBodyExecutor[El <: HList, In <: HList, Bd, ROut <: HList, POut <: HList, CIn <: HList, F[_]: Monad, FOut]
    (implicit encoder: EntityEncoder[F, FOut], 
              decoder: EntityDecoder[F, Bd],
              _prepend: Prepend.Aux[ROut, Bd :: HNil, POut], 
              _eqProof: POut =:= CIn) = new ReqBodyExecutor[El, In, Bd, ROut, POut, CIn, F, FOut] {
    type R   = Request[F]
    type Out = F[Response[F]]

    implicit val prepend = _prepend
    implicit val eqProof = _eqProof

    private val dsl = Http4sDsl[F]
    import dsl._

    def apply(req: R, eReq: EndpointRequest, endpoint: Endpoint[El, In, (BodyType[Bd], ROut), CIn, F, FOut]): Either[ExtractionError, Out] = {
      extract(eReq, endpoint).map { case (_, extracted) =>
        for {
          body     <- req.as[Bd]
          response <- execute(extracted, body, endpoint)

          r <- Ok(response)
        } yield r
      }
    }
  }

  implicit val mountEndpoints = new MountEndpoints[BlazeBuilder[IO], Request[IO], IO[Response[IO]]] {
    import org.http4s.dsl.io._

    type Out = IO[Server[IO]]

    def apply(server: ServerManager[BlazeBuilder[IO]], endpoints: List[Serve[Request[IO], IO[Response[IO]]]]): Out = {
      val service = HttpService[IO] {
        case request =>
          def execute(eps: List[Serve[Request[IO], IO[Response[IO]]]], eReq: EndpointRequest): IO[Response[IO]] = eps match {
            case collection.immutable.::(endpoint, tail) => endpoint(request, eReq) match {
              case Right(response)            => response
              case Left(RouteNotFound)        => execute(tail, eReq)
              case Left(BadRouteRequest(msg)) => BadRequest(msg)
            }

            case Nil => NotFound("uri = " + request.uri)
          }

          val eReq = EndpointRequest(
            request.method.name, 
            request.uri.path.split("/").tail.toList, 
            request.uri.multiParams.map { case (key, value) => key -> value.toList },
            request.headers.toList.map(header => header.name.toString -> header.value)(collection.breakOut)
          )
          execute(endpoints, eReq)
      }

      server.server.bindHttp(server.port, server.host).mountService(service, "/").start
    }
  }
}
