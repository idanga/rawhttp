package rawhttp.core

import io.kotlintest.matchers.beEmpty
import io.kotlintest.matchers.endWith
import io.kotlintest.matchers.include
import io.kotlintest.matchers.should
import io.kotlintest.matchers.shouldBe
import io.kotlintest.matchers.shouldEqual
import io.kotlintest.matchers.shouldThrow
import io.kotlintest.properties.Row8
import io.kotlintest.properties.Table1
import io.kotlintest.properties.forAll
import io.kotlintest.properties.headers
import io.kotlintest.properties.row
import io.kotlintest.properties.table
import org.junit.Test
import rawhttp.core.errors.InvalidHttpHeader

typealias UriExample = Row8<String, String?, String?, String?, Int, String?, String?, String?>

class HttpMetadataParserTest {

    private val parser = HttpMetadataParser(RawHttpOptions.defaultInstance())
    private val errorCreator = { error: String, line: Int -> throw InvalidHttpHeader("$error($line)") }

    @Test
    fun canParseEmptyHeader() {
        val headers = parser.parseHeaders("".byteInputStream(), errorCreator)
        headers.asMap().keys should beEmpty()
    }

    @Test
    fun canParseSimpleHeaders() {
        val examples = table(
                headers("HTTP Header", "Name", "Value"),
                row("Accept: application/json", "Accept", "application/json"),
                row("Accept: text/xml;q=1.0,app/json;q=0.8,image/png+x", "Accept", "text/xml;q=1.0,app/json;q=0.8,image/png+x"),
                row("Content-Length:  42", "Content-Length", "42"),
                row("Content-Length:42   ", "Content-Length", "42")
        )

        forAll(examples) { header, expectedName, expectedValue ->
            val headers = parser.parseHeaders(header.byteInputStream(), errorCreator)
            headers.asMap().size shouldBe 1
            headers[expectedName] shouldEqual listOf(expectedValue)
        }
    }

    @Test
    fun canParseManyHeaders() {
        val allHeaders = """
            Date: Thu, 9 Aug 2018 17:42:09 GMT
            Server: RawHTTP
            Cache-Control: no-cache
            Pragma: no-cache
            Content-Type: text/plain
            Content-Length: 23
            X-Color: red
            X-Color: blue
        """.trimIndent()

        val headers = parser.parseHeaders(allHeaders.byteInputStream(), errorCreator)

        headers.asMap().keys shouldEqual setOf(
                "DATE", "SERVER", "CACHE-CONTROL", "PRAGMA",
                "CONTENT-TYPE", "CONTENT-LENGTH", "X-COLOR")

        headers["Date"] shouldEqual listOf("Thu, 9 Aug 2018 17:42:09 GMT")
        headers["Server"] shouldEqual listOf("RawHTTP")
        headers["Cache-Control"] shouldEqual listOf("no-cache")
        headers["Pragma"] shouldEqual listOf("no-cache")
        headers["Content-Type"] shouldEqual listOf("text/plain")
        headers["Content-Length"] shouldEqual listOf("23")
        headers["X-Color"] shouldEqual listOf("red", "blue")
    }

    @Test
    fun canParseWeirdHeaderNames() {
        val weirdNames = listOf("A!", "#H", "$$$", "4%0", "&X", "'A'", "*Y", "A+B", "A..", "A^", "X_Y", "`a", "X|Y", "~")

        forAll(weirdNames.asTable()) { weirdName ->
            val headers = parser.parseHeaders("$weirdName: value".byteInputStream(), errorCreator)
            headers.asMap().size shouldBe 1
            headers[weirdName] shouldEqual listOf("value")
        }
    }

    @Test
    fun cannotParseInvalidHeaderNames() {
        val badNames = listOf("A\"", "{A}", "?", "A()", "A/", ";B", "<A>", "C=A", "A@B", "[]", "A\\B", "ÅB", "Ä", "ão")

        forAll(badNames.asTable()) { badName ->
            val error = shouldThrow<InvalidHttpHeader> {
                val headers = parser.parseHeaders("$badName: value".byteInputStream(charset = Charsets.ISO_8859_1), errorCreator)
                print("Should have failed: $headers")
            }

            error.message!! should include("Illegal character in HTTP header name")
            error.message!! should endWith("(1)") // line number
        }
    }

    @Test
    fun canParseWeirdHeaderValues() {
        val weirdValues = listOf("A!", "#H", "$$$", "4%0", "&X", "'A'", "*Y", "A+B", "A..", "A^", "X_Y", "`a", "X|Y",
                "~", "(abc)", "[hi]", "a, b, c", "<html>", "A;{b}", "A???", "@2@", "1;a=b;c=d", "z\tx",
                "\u00DEbc", "ab\u00FFc", "p\u00E3o")

        forAll(weirdValues.asTable()) { weirdValue ->
            val headers = parser.parseHeaders("A: $weirdValue".byteInputStream(charset = Charsets.ISO_8859_1), errorCreator)
            headers.asMap().size shouldBe 1
            headers["A"] shouldEqual listOf(weirdValue)
        }
    }

    @Test
    fun cannotParseInvalidHeaderValues() {
        val badValues = listOf("hi\u007F", "ab\u0000c")

        forAll(badValues.asTable()) { badValue ->
            val error = shouldThrow<InvalidHttpHeader> {
                val headers = parser.parseHeaders("A: $badValue".byteInputStream(charset = Charsets.ISO_8859_1), errorCreator)
                print("Should have failed: $headers")
            }

            error.message!! should include("Illegal character in HTTP header value")
            error.message!! should endWith("(1)") // line number
        }
    }

    @Test
    fun headerNameMustNotContainWhitespace() {
        val allHeaders = """
            Date: Thu, 9 Aug 2018 17:42:09 GMT
            Server : RawHTTP
            Cache-Control: no-cache
        """.trimIndent()

        val error = shouldThrow<InvalidHttpHeader> {
            val headers = parser.parseHeaders(allHeaders.byteInputStream(), errorCreator)
            print("Should have failed: $headers")
        }

        error.message!! should include("Illegal character in HTTP header name")
        error.message!! should endWith("(2)") // line number
    }

    @Test
    fun canLimitHeaderNameLength() {
        val lowMaxHeaderNameLimitParser = HttpMetadataParser(RawHttpOptions.newBuilder()
                .withHttpHeadersOptions()
                .withMaxHeaderNameLength(6)
                .done().build())

        val examples = table(
                headers("Header Name", "Should pass"),
                row("X", true),
                row("Conten", true),
                row("Content", false),
                row("Content-Type", false),
                row("AVERYLARGEHEADERNAMEWHICHMUSTNOTBEACCEPTED", false)
        )

        forAll(examples) { headerName, shouldPass ->
            val parse = {
                lowMaxHeaderNameLimitParser.parseHeaders(
                        "$headerName: OK".byteInputStream(), errorCreator)
            }
            if (shouldPass) {
                val headers = parse()
                headers.asMap().size shouldBe 1
                headers[headerName] shouldEqual listOf("OK")
            } else {
                val error = shouldThrow<InvalidHttpHeader> {
                    val headers = parse()
                    print("Should have failed: $headers")
                }
                error.message!! shouldBe "Header name is too long(1)"
            }
        }
    }

    @Test
    fun canLimitHeaderValueLength() {
        val lowMaxHeaderNameLimitParser = HttpMetadataParser(RawHttpOptions.newBuilder()
                .withHttpHeadersOptions()
                .withMaxHeaderValueLength(6)
                .done().build())

        val examples = table(
                headers("Header Value", "Should pass"),
                row("X", true),
                row("Conten", true),
                row("Content", false),
                row("Content-Type", false),
                row("AVERYLARGEHEADERNAMEWHICHMUSTNOTBEACCEPTED", false)
        )

        forAll(examples) { headerValue, shouldPass ->
            val parse = {
                lowMaxHeaderNameLimitParser.parseHeaders(
                        "Header: $headerValue".byteInputStream(), errorCreator)
            }
            if (shouldPass) {
                val headers = parse()
                headers.asMap().size shouldBe 1
                headers["Header"] shouldEqual listOf(headerValue)
            } else {
                val error = shouldThrow<InvalidHttpHeader> {
                    val headers = parse()
                    print("Should have failed: $headers")
                }
                error.message!! shouldBe "Header value is too long(1)"
            }
        }
    }

    @Test
    fun canValidateHeaders() {
        val nonDuplicatesAllowedParser = HttpMetadataParser(RawHttpOptions.newBuilder()
                .withHttpHeadersOptions()
                .withValidator { headers ->
                    val names = mutableSetOf<String>()
                    headers.forEach { name, _ ->
                        val isDuplicate = !names.add(name)
                        if (isDuplicate) {
                            throw InvalidHttpHeader("Duplicate header: $name")
                        }
                    }
                }
                .done()
                .build())

        // duplicate headers should FAIL
        val duplicateHeaders = """
            Date: Thu, 9 Aug 2018 17:42:09 GMT
            Server: RawHTTP
            X-Color: red
            X-Color: blue
        """.trimIndent()

        val error = shouldThrow<InvalidHttpHeader> {
            val headers = nonDuplicatesAllowedParser.parseHeaders(duplicateHeaders.byteInputStream(), errorCreator)
            print("Should have failed: $headers")
        }

        error.message shouldEqual "Duplicate header: X-Color"

        // non-duplicate headers should PASS
        val allHeaders = """
            Date: Thu, 9 Aug 2018 17:42:09 GMT
            Server: RawHTTP
            X-Color: red
            Y-Color: blue
        """.trimIndent()

        val headers = nonDuplicatesAllowedParser.parseHeaders(allHeaders.byteInputStream(), errorCreator)

        // make sure the parsing worked
        headers.asMap().keys shouldEqual setOf("DATE", "SERVER", "X-COLOR", "Y-COLOR")
    }

    @Test
    fun canParseURIsLenient() {
        val table = table(
                headers("URI spec", "scheme", "userInfo", "host", "port", "path", "query", "fragment"),
                UriExample("hi", "http", null, "hi", -1, "", null, null),
                UriExample("hi.com", "http", null, "hi.com", -1, "", null, null),
                UriExample("hi.com:80", "http", null, "hi.com", 80, "", null, null),
                UriExample("https://hi.com:80/", "https", null, "hi.com", 80, "/", null, null),
                UriExample("hi.com/hello", "http", null, "hi.com", -1, "/hello", null, null),
                UriExample("hi?", "http", null, "hi", -1, "", "", null),
                UriExample("hi?abc", "http", null, "hi", -1, "", "abc", null),
                UriExample("hi?a b", "http", null, "hi", -1, "", "a%20b", null),
                UriExample("hi#", "http", null, "hi", -1, "", null, ""),
                UriExample("hi#def", "http", null, "hi", -1, "", null, "def"),
                UriExample("hi#d^f", "http", null, "hi", -1, "", null, "d%5Ef"),
                UriExample("hi#a?b", "http", null, "hi", -1, "", null, "a?b"),
                UriExample("hi?a#d", "http", null, "hi", -1, "", "a", "d"),
                UriExample("hi?a!#d@f", "http", null, "hi", -1, "", "a!", "d@f"),
                UriExample("/user/{user-id}", "http", null, null, -1, "/user/%7Buser-id%7D", null, null),
                UriExample("/id/{0x0}?encoded=%2F%2Fencoded%3Fa%3Db%26c%3Dd&another=%7B%22json%22%3A%20null%7D", "http", null, null, -1,
                        "/id/%7B0x0%7D", "encoded=%2F%2Fencoded%3Fa%3Db%26c%3Dd&another=%7B%22json%22%3A%20null%7D", null),
                UriExample("x://admin@hello", "x", "admin", "hello", -1, "", null, null),
                UriExample("x://admin:pass@hello.com/hi?boo&bar#abc", "x", "admin:pass", "hello.com", -1, "/hi", "boo&bar", "abc"),
                UriExample("https://admin:pass@hello:8443/hi?boo&bar#abc", "https", "admin:pass", "hello", 8443, "/hi", "boo&bar", "abc"),
                UriExample("http://0.0.0.0:8080", "http", null, "0.0.0.0", 8080, "", null, null),
                UriExample("0.0.0.0:8080", "http", null, "0.0.0.0", 8080, "", null, null),
                UriExample("renato:pass@192.168.0.1:83", "http", "renato:pass", "192.168.0.1", 83, "", null, null),
                UriExample("[2001:db8:85a3:0:0:8a2e:370:7334]", "http", null, "[2001:db8:85a3:0:0:8a2e:370:7334]", -1, "", null, null),
                UriExample("[::8a2e:370:7334]:43", "http", null, "[::8a2e:370:7334]", 43, "", null, null),
                UriExample("ftp://user:pass@[::8a2e:370:7334]/hi?ho", "ftp", "user:pass", "[::8a2e:370:7334]", -1, "/hi", "ho", null),
                UriExample("ftp://[::8a2e:370:7334]#ho", "ftp", null, "[::8a2e:370:7334]", -1, "", null, "ho")
        )

        val lenientParser = HttpMetadataParser(RawHttpOptions.newBuilder()
                .allowIllegalStartLineCharacters()
                .build())

        forAll(table) { uriSpec, scheme, userInfo, host, port, path, query, fragment ->
            val uri = lenientParser.parseUri(uriSpec)
            uri.scheme shouldEqual scheme
            uri.userInfo shouldEqual userInfo
            uri.host shouldEqual host
            uri.port shouldEqual port
            uri.rawPath shouldEqual path
            uri.rawQuery shouldEqual query
            uri.rawFragment shouldEqual fragment
        }
    }

    @Test
    fun canParseQueryStrings() {
        val table = table(headers("Query String", "Expected query parameters"),
                row("", mapOf()),
                row("&", mapOf()),
                row("=", mapOf("" to listOf(""))),
                row("hello", mapOf("hello" to listOf<String>())),
                row("hello=", mapOf("hello" to listOf(""))),
                row("hello=true", mapOf("hello" to listOf("true"))),
                row("a=1&b=2", mapOf("a" to listOf("1"), "b" to listOf("2"))),
                row("a=1&a=2&b=3&a=4", mapOf("a" to listOf("1", "2", "4"), "b" to listOf("3"))),
                row("hello=hi there", mapOf("hello" to listOf("hi there"))),
                row("hello there=hi", mapOf("hello there" to listOf("hi"))))

        forAll(table) { queryString, expectedParameters ->
            val parameters = parser.parseQueryString(queryString)
            parameters shouldEqual expectedParameters
        }
    }

    private fun <E> List<E>.asTable(name: String = "value"): Table1<E> {
        val rows = map { row(it) }.toTypedArray()
        return table(headers(name), *rows)
    }

}
