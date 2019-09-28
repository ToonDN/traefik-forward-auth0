package dniel.forwardauth.infrastructure.spring

import dniel.forwardauth.application.AuthorizeHandler
import dniel.forwardauth.application.LoggingHandler
import dniel.forwardauth.infrastructure.spring.exceptions.ApplicationErrorException
import dniel.forwardauth.infrastructure.spring.exceptions.PermissionDeniedException
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.util.MultiValueMap
import org.springframework.web.bind.annotation.*
import java.util.stream.Collectors
import javax.servlet.http.Cookie
import javax.servlet.http.HttpServletResponse


@RestController
class AuthorizeController(val authorizeHandler: AuthorizeHandler) {
    private val LOGGER = LoggerFactory.getLogger(this.javaClass)

    /**
     * Authorize Endpoint.
     * This endpoint is used by traefik forward properties to authorize requests.
     * It will return 200 for requests that has a valid JWT_TOKEN and will
     * redirect other to authenticate at Auth0.
     */
    @RequestMapping("/authorize", method = [RequestMethod.GET])
    fun authorize(@RequestHeader headers: MultiValueMap<String, String>,
                  @CookieValue("ACCESS_TOKEN", required = false) accessTokenCookie: String?,
                  @CookieValue("JWT_TOKEN", required = false) userinfoCookie: String?,
                  @RequestHeader("Accept") acceptContent: String?,
                  @RequestHeader("x-requested-with") requestedWithHeader: String?,
                  @RequestHeader("x-forwarded-host") forwardedHostHeader: String,
                  @RequestHeader("x-forwarded-proto") forwardedProtoHeader: String,
                  @RequestHeader("x-forwarded-uri") forwardedUriHeader: String,
                  @RequestHeader("x-forwarded-method") forwardedMethodHeader: String,
                  response: HttpServletResponse): ResponseEntity<Unit> {

        printHeaders(headers)
        return authenticateToken(acceptContent, requestedWithHeader,
                accessTokenCookie, userinfoCookie, forwardedMethodHeader,
                forwardedHostHeader, forwardedProtoHeader, forwardedUriHeader, response)
    }

    private fun authenticateToken(acceptContent: String?, requestedWithHeader: String?, accessToken: String?,
                                  idToken: String?, method: String, host: String, protocol: String,
                                  uri: String, response: HttpServletResponse): ResponseEntity<Unit> {
        val command: AuthorizeHandler.AuthorizeCommand = AuthorizeHandler.AuthorizeCommand(accessToken, idToken, protocol, host, uri, method)
        val authorizeResult = LoggingHandler(authorizeHandler).handle(command)

        return when (authorizeResult) {
            is AuthorizeHandler.AuthEvent.AccessDenied -> throw PermissionDeniedException()
            is AuthorizeHandler.AuthEvent.Error -> throw ApplicationErrorException()

            is AuthorizeHandler.AuthEvent.NeedRedirect -> {
                if ((acceptContent != null && acceptContent.contains("application/json")) ||
                        requestedWithHeader != null && requestedWithHeader == "XMLHttpRequest") {
                    ResponseEntity.status(HttpStatus.FORBIDDEN).build()
                } else {
                    // add the nonce value to the request to be able to retrieve ut again on the singin endpoint.
                    val nonceCookie = Cookie("AUTH_NONCE", authorizeResult.nonce.value)
                    nonceCookie.domain = authorizeResult.cookieDomain
                    nonceCookie.maxAge = 60
                    nonceCookie.isHttpOnly = true
                    nonceCookie.path = "/"
                    response.addCookie(nonceCookie)
                    LOGGER.debug("Redirect to ${authorizeResult.authorizeUrl}")
                    ResponseEntity.status(HttpStatus.TEMPORARY_REDIRECT).location(authorizeResult.authorizeUrl).build()
                }
            }
            is AuthorizeHandler.AuthEvent.AccessGranted -> {
                val builder = ResponseEntity.noContent()
                builder.build()
            }
            else -> throw ApplicationErrorException()
        }

/*
            // add the authorization bearer header with token so that
            // the backend api receives it and knows that the user has been authenticated.
            builder.header("Authorization", "Bearer ${accessToken}")
            validIdTokenEvent.userinfo.forEach { k, v ->
                val headerName = "X-Forwardauth-${k.capitalize()}"
                LOGGER.trace("Add header ${headerName} with value ${v}")
                builder.header(headerName, v)
            }
*/

    }


    private fun printHeaders(headers: MultiValueMap<String, String>) {
        if (LOGGER.isTraceEnabled) {
            headers.forEach { (key, value) -> LOGGER.trace(String.format("Header '%s' = %s", key, value.stream().collect(Collectors.joining("|")))) }
        }
    }
}