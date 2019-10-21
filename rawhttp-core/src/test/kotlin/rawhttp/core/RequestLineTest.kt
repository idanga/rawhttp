package rawhttp.core

import io.kotlintest.matchers.fail
import io.kotlintest.matchers.shouldBe
import io.kotlintest.matchers.shouldThrow
import io.kotlintest.properties.forAll
import io.kotlintest.properties.headers
import io.kotlintest.properties.row
import io.kotlintest.properties.table
import io.kotlintest.specs.StringSpec
import rawhttp.core.errors.InvalidHttpRequest

class RequestLineTest : StringSpec({

    val metadataParser = HttpMetadataParser(RawHttpOptions.defaultInstance())

    val strictMetadataParser = HttpMetadataParser(RawHttpOptions.newBuilder()
            .doNotAllowNewLineWithoutReturn()
            .doNotIgnoreLeadingEmptyLine()
            .doNotInsertHttpVersionIfMissing()
            .build())

    "Can parse legal request-line (allow missing HTTP version)" {
        val table = table(
                headers("Request line", "Expected version", "Expected method", "Expected path", "Expected String"),
                row("GET /", HttpVersion.HTTP_1_1, "GET", "/", "GET / HTTP/1.1"),
                row("POST /hello", HttpVersion.HTTP_1_1, "POST", "/hello", "POST /hello HTTP/1.1"),
                row("POST /hello HTTP/1.1", HttpVersion.HTTP_1_1, "POST", "/hello", "POST /hello HTTP/1.1"),
                row("do /hello HTTP/1.0", HttpVersion.HTTP_1_0, "do", "/hello", "do /hello HTTP/1.0"),
                row("GET /hello?a=1&b=2 HTTP/1.0", HttpVersion.HTTP_1_0, "GET", "/hello", "GET /hello?a=1&b=2 HTTP/1.0"))

        forAll(table) { requestLine, expectedVersion, expectedMethod, expectedPath, expectedString ->
            try {
                metadataParser.parseRequestLine(requestLine).run {
                    httpVersion shouldBe expectedVersion
                    method shouldBe expectedMethod
                    uri.path shouldBe expectedPath
                    toString() shouldBe expectedString
                }
            } catch (e: InvalidHttpRequest) {
                fail(e.toString())
            }
        }
    }

    "Can parse legal request-line (allow illegal characters)" {
        val illegalCharParser = HttpMetadataParser(RawHttpOptions.newBuilder()
                .allowIllegalStartLineCharacters()
                .build())

        val table = table(
                headers("Request line", "Expected version", "Expected method", "Expected path", "Expected String"),
                row("GET /hi there HTTP/1.1", HttpVersion.HTTP_1_1, "GET", "/hi%20there", "GET /hi%20there HTTP/1.1"),
                row("POST /api/users/test@example.com HTTP/1.1", HttpVersion.HTTP_1_1,
                        "POST", "/api/users/test@example.com", "POST /api/users/test@example.com HTTP/1.1"),
                row("GET /user/{user-id} HTTP/1.1", HttpVersion.HTTP_1_1, "GET", "/user/%7Buser-id%7D", "GET /user/%7Buser-id%7D HTTP/1.1")
        )

        forAll(table) { requestLine, expectedVersion, expectedMethod, expectedPath, expectedString ->
            try {
                illegalCharParser.parseRequestLine(requestLine).run {
                    httpVersion shouldBe expectedVersion
                    method shouldBe expectedMethod
                    uri.rawPath shouldBe expectedPath
                    toString() shouldBe expectedString
                }
            } catch (e: InvalidHttpRequest) {
                fail(e.toString())
            }
        }
    }

    "Cannot parse illegal request-line (allow missing HTTP version)" {
        val table = table(headers("Request line", "Expected error"),
                row("", "No content"),
                row("/", "Invalid request line"),
                row("GET", "Invalid request line"),
                row("POST ", "Missing request target"),
                row("POST  / HTTP/1.1", "Invalid request target: Illegal character in authority at index 0: ' /'"),
                row("/Hi /", "Invalid method name: illegal character at index 0: '/Hi'"),
                row("POST /hi HTTP/1.2", "Unknown HTTP version"),
                row("POST /hi HTTP/1.2 HTTP/1.1",
                        "Invalid request target: Illegal character in path at index 3: '/hi HTTP/1.2'"))

        forAll(table) { requestLine, expectedError ->
            val error = shouldThrow<InvalidHttpRequest> { metadataParser.parseRequestLine(requestLine) }
            error.message shouldBe expectedError
            error.lineNumber shouldBe if (requestLine.isEmpty()) 0 else 1
        }
    }

    "Can parse legal request-line (strict)" {
        val table = table(
                headers("Request line", "Expected version", "Expected method", "Expected path", "Expected String"),
                row("POST /hello HTTP/1.1", HttpVersion.HTTP_1_1, "POST", "/hello", "POST /hello HTTP/1.1"),
                row("do /hello HTTP/1.0", HttpVersion.HTTP_1_0, "do", "/hello", "do /hello HTTP/1.0"),
                row("GET /hello?field=encoded%20value HTTP/1.0", HttpVersion.HTTP_1_0, "GET", "/hello",
                        "GET /hello?field=encoded%20value HTTP/1.0"),
                row("GET /hello?a=1&b=2 HTTP/1.0", HttpVersion.HTTP_1_0, "GET", "/hello", "GET /hello?a=1&b=2 HTTP/1.0"))

        forAll(table) { requestLine, expectedVersion, expectedMethod, expectedPath, expectedString ->
            try {
                strictMetadataParser.parseRequestLine(requestLine).run {
                    httpVersion shouldBe expectedVersion
                    method shouldBe expectedMethod
                    uri.path shouldBe expectedPath
                    toString() shouldBe expectedString
                }
            } catch (e: InvalidHttpRequest) {
                fail(e.toString())
            }
        }
    }

    "Cannot parse illegal request-line (strict)" {
        val table = table(headers("Request line", "Expected error"),
                row("", "No content"),
                row("/", "Invalid request line"),
                row("GET", "Invalid request line"),
                row("POST  ", "Unknown HTTP version"),
                row("POST  / HTTP/1.1", "Invalid request target: Illegal character in authority at index 0: ' /'"),
                row("GET /", "Missing HTTP version"),
                row("POST /hello", "Missing HTTP version"),
                row("/Hi / HTTP/1.1", "Invalid method name: illegal character at index 0: '/Hi'"),
                row("GÅ / HTTP/1.1", "Invalid method name: illegal character at index 1: 'GÅ'"),
                row("GET /hello?field=encoded value HTTP/1.0",
                        "Invalid request target: Illegal character in query at index 20: '/hello?field=encoded value'"),
                row("POST /hi HTTP/1.2", "Unknown HTTP version"),
                row("POST /hi HTTP/1.2 HTTP/1.1",
                        "Invalid request target: Illegal character in path at index 3: '/hi HTTP/1.2'"))

        forAll(table) { requestLine, expectedError ->
            val error = shouldThrow<InvalidHttpRequest> { strictMetadataParser.parseRequestLine(requestLine) }
            error.message shouldBe expectedError
            error.lineNumber shouldBe if (requestLine.isEmpty()) 0 else 1
        }
    }

})