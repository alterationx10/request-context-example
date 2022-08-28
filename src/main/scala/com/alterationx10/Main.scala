package com.alterationx10

import zio._
import zhttp.http._
import zhttp.service.Server
import zhttp.http.middleware.Auth

// A model of something we may want to use in out logic, after processed via Middleware
case class RequestContext(
    authHeader: Option[Auth.Credentials],
    validatedUser: Option[String]
)

// A Service to manage the current context
trait RequestContextService {
  def getCurrentContext: UIO[RequestContext]
  def setCurrentContext(ctx: RequestContext): UIO[Unit]
}

// An implementation, that uses a FiberRef
final case class RequestContextServiceLive(ref: FiberRef[RequestContext])
    extends RequestContextService {

  override def getCurrentContext: UIO[RequestContext] = ref.get

  override def setCurrentContext(ctx: RequestContext): UIO[Unit] = ref.set(ctx)

}

object RequestContextServiceLive {
  // Provide an "empty" context when we start up our layer
  val live: ZLayer[Any, Nothing, RequestContextService] = ZLayer.scoped {
    for {
      ref <- FiberRef.make[RequestContext](RequestContext(None, None))
    } yield RequestContextServiceLive(ref)
  }
}

// Custom middleware
object RequestContextMiddleware {

  // This only copies the basic auth Credentials into our context - validation happens later
  def injectAuthHeader: Middleware[
    RequestContextService,
    Nothing,
    Request,
    Response,
    Request,
    Response
  ] = new Middleware[
    RequestContextService,
    Nothing,
    Request,
    Response,
    Request,
    Response
  ] {

    override def apply[R1 <: RequestContextService, E1](
        http: Http[R1, E1, Request, Response]
    ): Http[R1, E1, Request, Response] = {
      http.contramapZIO[R1, E1, Request] { request =>
        for {
          rcs <- ZIO.service[RequestContextService]
          _ <- rcs.setCurrentContext(
            RequestContext(request.basicAuthorizationCredentials, None)
          )
          _ <- rcs.getCurrentContext.debug("inject")
        } yield request
      }
    }
  }

  // We COULD add our own validation here, but to keep the example simple, we're going to cheat at use the Middleware.basicauth
  // to actually validate, so here we'll just copy the authenticated user name into our context.
  def validateCurrentContext: Middleware[
    RequestContextService,
    Nothing,
    Request,
    Response,
    Request,
    Response
  ] = new Middleware[
    RequestContextService,
    Nothing,
    Request,
    Response,
    Request,
    Response
  ] {

    override def apply[R1 <: RequestContextService, E1](
        http: Http[R1, E1, Request, Response]
    ): Http[R1, E1, Request, Response] = {
      http.contramapZIO[R1, E1, Request] { request =>
        for {
          rcs <- ZIO.service[RequestContextService]
          ctx <- rcs.getCurrentContext
          validated = ctx.authHeader.map(_.uname)
          _ <- rcs.setCurrentContext(ctx.copy(validatedUser = validated))
          _ <- rcs.getCurrentContext.debug("validate")
        } yield request
      }
    }

  }

}

object Main extends ZIOAppDefault {

  // The ordering here looks strange, but I believe it's because of the order the .contramaps run in our middleware.
  // You can see it is correct from the debug lines printed when run.
  val contextMiddleware: Middleware[
    RequestContextService,
    Nothing,
    Request,
    Response,
    Request,
    Response
  ] =
    RequestContextMiddleware.validateCurrentContext >>>
      Middleware.basicAuth("me", "mypass") >>>
      RequestContextMiddleware.injectAuthHeader

  val app: Http[RequestContextService, Nothing, Request, Response] =
    Http.collectZIO[Request] { case Method.GET -> !! =>
      for {
        rcs <- ZIO.service[RequestContextService]
        ctx <- rcs.getCurrentContext.debug("app")
      } yield Response.text(
        ctx.validatedUser.fold("How did you get here?")(u =>
          s"Hello, $u"
        ) // do something with info stashed in the context!
      )
    } @@ contextMiddleware

  val program: ZIO[RequestContextService, Throwable, ExitCode] = for {
    _ <- Console.printLine(s"Starting server on http://localhost:9000")
    _ <- Server.start(9000, app)
  } yield ExitCode.success

  override def run: ZIO[ZIOAppArgs with Scope, Any, Any] =
    program.provide(RequestContextServiceLive.live)

}
