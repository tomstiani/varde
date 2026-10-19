package no.varde.app.inbox

import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class RichMetricTest {
    private val certificate = HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
    private val serverCertificates = HandshakeCertificates.Builder().heldCertificate(certificate).build()
    private val trust = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
    private fun server() = MockWebServer().apply { useHttps(serverCertificates.sslSocketFactory(), false) }
    private fun backend() = HttpBackend(OkHttpClient.Builder().sslSocketFactory(trust.sslSocketFactory(), trust.trustManager).build())
    private val metric = """{"type":"measurement","label":"CPU usage","value":83.00,"unit":"%","source":null,"measuredAt":"2026-10-19T12:00:00.123456789Z"}"""
    private val components = """[{"type":"metric","dataIndex":0,"reading":"value"},{"type":"text","field":"summary"}]"""

    private fun body(data: String = "[$metric]", view: String = """{"schemaVersion":1,"components":$components}""") =
        """{"id":"one","acceptedAt":"2026-10-19T12:03:00Z","original":{"schemaVersion":1,"eventId":"synthetic","title":"Synthetic alert","summary":"  Full supplied summary.\nNo paraphrase.  ","priority":"high","observedAt":"2026-10-19T12:02:00Z","dataDefaults":{"source":"monitor","resource":"host-a","collector":"agent-a"},"data":$data},"presentation":{"state":"rich","revision":1},"richView":$view}"""

    @Test fun readsExactMetricAndCompleteSummaryWithoutReplacingOriginals() = runTest {
        server().use { server ->
            server.start()
            server.enqueue(MockResponse().setBody(body()))
            val detail = backend().read(Connection.create("Synthetic lab", server.url("/").toString(), "fake-key"), "one")
            assertEquals("Synthetic alert", detail.row.title)
            assertEquals("  Full supplied summary.\nNo paraphrase.  ", detail.summary)
            val metric = detail.richComponents[0] as RichMetric
            assertEquals("83.00", metric.value)
            assertNull(metric.source)
            assertEquals("host-a", metric.resource)
            assertEquals("agent-a", metric.collector)
            assertEquals("2026-10-19T12:00:00.123456789Z", metric.measuredAt)
            assertEquals(detail.summary, (detail.richComponents[1] as RichText).text)
        }
    }

    @Test fun historyReadsRichStateWithoutFetchingOriginalOrRichBodies() = runTest {
        server().use { server ->
            server.start()
            server.enqueue(MockResponse().setBody("""{"items":[{"id":"one","acceptedAt":"2026-10-19T12:03:00Z","title":"Synthetic alert","priority":"high","observedAt":"2026-10-19T12:00:00Z","presentation":{"state":"rich","revision":1}}],"nextCursor":null}"""))
            val page = backend().history(Connection.create("Synthetic lab", server.url("/").toString(), "fake-key"))
            assertEquals(Presentation("rich", 1), page.items.single().presentation)
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun retainsLargeExactDecimalsAndDoesNotMergeConflictingSources() = runTest {
        server().use { server ->
            server.start()
            val data = """[{"type":"measurement","label":"CPU","value":9007199254740993,"source":"monitor-a","measuredAt":"2026-10-19T12:00:00+23:59"},{"type":"measurement","label":"CPU","value":9007199254740992,"source":"monitor-b"}]"""
            val view = """{"schemaVersion":1,"components":[{"type":"metric","dataIndex":1,"reading":"value"},{"type":"metric","dataIndex":0,"reading":"value"}]}"""
            server.enqueue(MockResponse().setBody(body(data, view)))
            val detail = backend().read(Connection.create("Synthetic lab", server.url("/").toString(), "fake-key"), "one")
            assertEquals(listOf("9007199254740992", "9007199254740993"), detail.richComponents.map { (it as RichMetric).value })
            assertEquals(listOf("monitor-b", "monitor-a"), detail.richComponents.map { (it as RichMetric).source })
            assertNull((detail.richComponents[0] as RichMetric).measuredAt)
            assertNull((detail.richComponents[0] as RichMetric).unit)
        }
    }

    @Test fun invalidTypedFactsRejectTheWholeViewWithoutLosingOriginals() = runTest {
        server().use { server ->
            server.start()
            val connection = Connection.create("Synthetic lab", server.url("/").toString(), "fake-key")
            for (data in listOf(
                """[{"type":"measurement","label":"CPU","value":null}]""",
                """[{"type":"measurement","label":"CPU","value":1,"samples":[]}]""",
                """[{"type":"measurement","label":"CPU","value":1,"source":" "}]""",
                """[{"type":"measurement","label":"CPU","value":1e101}]""",
                """[{"type":"measurement","label":"CPU","value":1e-101}]""",
                """[{"type":"measurement","label":"CPU","value":12345678901234567890123456789012345}]""",
                """[{"type":"measurement","label":"CPU","value":1,"value":2}]""",
                """[{"type":"measurement","label":"CPU","value":1,"measuredAt":"2026-10-19T12:00:00-00:00"}]""",
                """[{"type":"measurement","label":"CPU","value":1,"measuredAt":"2026-10-19T12:00:00.1234567891Z"}]""",
                """[{"type":"measurement","label":"CPU","value":1,"measuredAt":"2026-02-30T12:00:00Z"}]""",
                """[{"type":"measurement","label":"\ud800","value":1}]""",
                """[{"type":"records","label":"Jobs","columns":[],"rows":[]}]""",
                """[{"type":"sequence","label":"Stages","steps":[]}]""",
            )) {
                server.enqueue(MockResponse().setBody(body(data = data)))
                val detail = backend().read(connection, "one")
                assertTrue("Accepted invalid typed facts: $data", detail.richComponents.isEmpty())
                assertEquals("Synthetic alert", detail.row.title)
                assertEquals("  Full supplied summary.\nNo paraphrase.  ", detail.summary)
            }
        }
    }

    @Test fun oneInvalidComponentRejectsTheWholeViewButKeepsOriginalText() = runTest {
        server().use { server ->
            server.start()
            val connection = Connection.create("Synthetic lab", server.url("/").toString(), "fake-key")
            for (view in listOf(
                """{"schemaVersion":2,"components":$components}""",
                """{"schemaVersion":1,"components":[{"type":"metric","dataIndex":0,"reading":"value"},{"type":"unknown"}]}""",
                """{"schemaVersion":1,"components":[{"type":"metric","dataIndex":1,"reading":"value"}]}""",
                """{"schemaVersion":1,"components":[{"type":"metric","dataIndex":0,"reading":"value","style":"red"}]}""",
                """{"schemaVersion":1,"components":[{"type":"metric","dataIndex":0,"reading":"value"},{"type":"metric","dataIndex":0,"reading":"value"}]}""",
                """{"schemaVersion":1,"components":[],"extra":true}""",
                """{"schemaVersion":1,"schemaVersion":1,"components":$components}""",
                """{"schemaVersion":1,"components":[{"type":"metric","dataIndex":0,"reading":"latestSample"}]}""",
                """{"schemaVersion":1,"components":$components${" ".repeat(8192)}}""",
                """{"schemaVersion":1,"components":$components,"deep":${"[".repeat(12)}0${"]".repeat(12)}}""",
                """{"schemaVersion":1,"components":[{"type":"metric","dataIndex":0.0,"reading":"value"}]}""",
                """{"schemaVersion":1,"components":[{"type":"text","field":"title"}]}""",
            )) {
                server.enqueue(MockResponse().setBody(body(view = view)))
                val detail = backend().read(connection, "one")
                assertTrue(detail.richComponents.isEmpty())
                assertEquals("Synthetic alert", detail.row.title)
                assertEquals("  Full supplied summary.\nNo paraphrase.  ", detail.summary)
            }
        }
    }
}
