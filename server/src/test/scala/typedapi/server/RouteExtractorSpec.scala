package typedapi.server

import typedapi.shared._
import shapeless.{HList, HNil}
import org.specs2.mutable.Specification

final class RouteExtractorSpec extends Specification {

  case class Foo(name: String)

  def extract[H <: HList, Fold, El <: HList, In <: HList, ROut, Out]
    (api: FinalCons[H])
    (implicit folder: TypeLevelFoldLeft.Aux[H, (HNil, HNil), Fold],
              ev: FoldResultEvidence.Aux[Fold, El, In, Out],
              extractor: RouteExtractor.Aux[El, In, HNil, ROut]): RouteExtractor.Aux[El, In, HNil, ROut] = extractor

  "determine routes defined by requests and extract included data (segments, queries, headers)" >> {
    "no data" >> {
      val ext = extract(:= :> "hello" :> "world" :> Get[Foo])
      
      ext(EndpointRequest("GET", List("hello", "world"), Map.empty, Map.empty), Set.empty, HNil) === Right(HNil)
      ext(EndpointRequest("GET", List("hello", "wrong"), Map.empty, Map.empty), Set.empty, HNil) === RouteExtractor.NotFoundE
      ext(EndpointRequest("GET", List("hello"), Map.empty, Map.empty), Set.empty, HNil) === RouteExtractor.NotFoundE
      ext(EndpointRequest("GET", Nil, Map.empty, Map.empty), Set.empty, HNil) === RouteExtractor.NotFoundE
    }

    "segments" >> {
      val ext = extract(:= :> "foo" :> Segment[Int]('age) :> Get[Foo])

      ext(EndpointRequest("GET", List("foo", "0"), Map.empty, Map.empty), Set.empty, HNil) === Right(0 :: HNil)
      ext(EndpointRequest("GET", List("foo", "wrong"), Map.empty, Map.empty), Set.empty, HNil) === RouteExtractor.NotFoundE
      ext(EndpointRequest("GET", List("foo"), Map.empty, Map.empty), Set.empty, HNil) === RouteExtractor.NotFoundE
      ext(EndpointRequest("GET", Nil, Map.empty, Map.empty), Set.empty, HNil) === RouteExtractor.NotFoundE
    }

    "queries" >> {
      val ext0 = extract(:= :> "foo" :> Query[Int]('age) :> Get[Foo])

      ext0(EndpointRequest("GET", List("foo"), Map("age" -> List("0")), Map.empty), Set.empty, HNil) === Right(0 :: HNil)
      ext0(EndpointRequest("GET", List("foo"), Map("age" -> List("wrong")), Map.empty), Set.empty, HNil) === RouteExtractor.BadRequestE("query 'age' has not type Int")
      ext0(EndpointRequest("GET", List("foo"), Map("wrong" -> List("0")), Map.empty), Set.empty, HNil) === RouteExtractor.BadRequestE("missing query 'age'")
      ext0(EndpointRequest("GET", List("foo"), Map.empty, Map.empty), Set.empty, HNil) === RouteExtractor.BadRequestE("missing query 'age'")
      ext0(EndpointRequest("GET", List("foo", "bar"), Map.empty, Map.empty), Set.empty, HNil) === RouteExtractor.NotFoundE

      val ext1 = extract(:= :> "foo" :> Query[Option[Int]]('age) :> Get[Foo])

      ext1(EndpointRequest("GET", List("foo"), Map("age" -> List("0")), Map.empty), Set.empty, HNil) === Right(Some(0) :: HNil)
      ext1(EndpointRequest("GET", List("foo"), Map("wrong" -> List("0")), Map.empty), Set.empty, HNil) === Right(None :: HNil)

      val ext2 = extract(:= :> "foo" :> Query[List[Int]]('age) :> Get[Foo])

      ext2(EndpointRequest("GET", List("foo"), Map("age" -> List("0", "1")), Map.empty), Set.empty, HNil) === Right(List(0, 1) :: HNil)
      ext2(EndpointRequest("GET", List("foo"), Map.empty, Map.empty), Set.empty, HNil) === Right(Nil :: HNil)
    }

    "headers" >> {
      val ext0 = extract(:= :> "foo" :> Header[Int]('age) :> Get[Foo])

      ext0(EndpointRequest("GET", List("foo"), Map.empty, Map("age" -> "0")), Set.empty, HNil) === Right(0 :: HNil)
      ext0(EndpointRequest("GET", List("foo"), Map.empty, Map("age" -> "wrong")), Set.empty, HNil) === RouteExtractor.BadRequestE("header 'age' has not type Int")
      ext0(EndpointRequest("GET", List("foo"), Map.empty, Map("wrong" -> "0")), Set.empty, HNil) === RouteExtractor.BadRequestE("missing header 'age'")
      ext0(EndpointRequest("GET", List("foo"), Map.empty, Map.empty), Set.empty, HNil) === RouteExtractor.BadRequestE("missing header 'age'")
      ext0(EndpointRequest("GET", List("foo", "bar"), Map.empty, Map.empty), Set.empty, HNil) === RouteExtractor.NotFoundE

      //raw
      val ext1 = extract(:= :> "foo" :> RawHeaders :> Get[Foo])

      ext1(EndpointRequest("GET", List("foo"), Map.empty, Map("age" -> "blah")), Set.empty, HNil) === Right(Map("age" -> "blah") :: HNil)
      ext1(EndpointRequest("GET", List("foo"), Map.empty, Map.empty), Set.empty, HNil) === RouteExtractor.BadRequestE("no raw headers left, but at least one expected")

      // headers and raw
      val ext2 = extract(:= :> "foo" :> Header[Int]('age) :> RawHeaders :> Get[Foo])

      ext2(EndpointRequest("GET", List("foo"), Map.empty, Map("age" -> "0")), Set.empty, HNil) === RouteExtractor.BadRequestE("no raw headers left, but at least one expected")
      ext2(EndpointRequest("GET", List("foo"), Map.empty, Map("age" -> "0", "foo" -> "bar")), Set.empty, HNil) === Right(0 :: Map("foo" -> "bar") :: HNil)

      val ext3 = extract(:= :> "foo" :> Header[Option[Int]]('age) :> Get[Foo])

      ext3(EndpointRequest("GET", List("foo"), Map.empty, Map("age" -> "0")), Set.empty, HNil) === Right(Some(0) :: HNil)
      ext3(EndpointRequest("GET", List("foo"), Map.empty, Map.empty), Set.empty, HNil) === Right(None :: HNil)
    }

    "body type" >> {
      val ext0 = extract(:= :> ReqBody[Foo] :> Put[Foo])

      ext0(EndpointRequest("PUT", Nil, Map.empty, Map.empty), Set.empty, HNil) === Right((BodyType[Foo], HNil))
      ext0(EndpointRequest("PUT", List("foo", "bar"), Map.empty, Map.empty), Set.empty, HNil) === RouteExtractor.NotFoundE

      val ext1 = extract(:= :> ReqBody[Foo] :> Post[Foo])

      ext1(EndpointRequest("POST", Nil, Map.empty, Map.empty), Set.empty, HNil) === Right((BodyType[Foo], HNil))
    }

    "methods" >> {
      val ext0 = extract(:= :> Get[Foo])

      ext0(EndpointRequest("GET", Nil, Map.empty, Map.empty), Set.empty, HNil) === Right(HNil)
      ext0(EndpointRequest("WRONG", Nil, Map.empty, Map.empty), Set.empty, HNil) === RouteExtractor.NotFoundE
      ext0(EndpointRequest("GET", List("foo"), Map.empty, Map.empty), Set.empty, HNil) === RouteExtractor.NotFoundE

      val ext1 = extract(:= :> Put[Foo])

      ext1(EndpointRequest("PUT", Nil, Map.empty, Map.empty), Set.empty, HNil) === Right(HNil)

      val ext2 = extract(:= :> Post[Foo])

      ext2(EndpointRequest("POST", Nil, Map.empty, Map.empty), Set.empty, HNil) === Right(HNil)

      val ext3 = extract(:= :> Delete[Foo])

      ext3(EndpointRequest("DELETE", Nil, Map.empty, Map.empty), Set.empty, HNil) === Right(HNil)
    }

    "combinations" >> {
      val ext0 = extract(:= :> "foo" :> Query[Int]('age) :> Header[String]('id) :> Get[Foo])

      ext0(EndpointRequest("GET", List("foo"), Map("age" -> List("0")), Map("id" -> "john")), Set.empty, HNil) === Right(0 :: "john" :: HNil)

      val ext1 = extract(:= :> Get[Foo])

      ext1(EndpointRequest("GET", Nil, Map.empty, Map.empty), Set.empty, HNil) === Right(HNil)
    }
  }
}
