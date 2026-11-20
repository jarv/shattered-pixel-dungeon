package com.shatteredpixel.shatteredpixeldungeon.desktop.api;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import static org.junit.Assert.*;

/** Exercises the real loopback transport without starting the graphical game. */
public class PlayerApiHttpTest {
	private PlayerApi api;
	private final HttpClient client = HttpClient.newHttpClient();

	@Before public void start() throws Exception {
		api = new PlayerApi(0, "contract-test-token");
	}

	@After public void stop() {
		if (api != null) api.close();
	}

	private HttpRequest.Builder request(String path) {
		return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + api.port() + path))
				.timeout(Duration.ofSeconds(5));
	}

	private void assertError(HttpRequest request, int status, String code) throws Exception {
		HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
		assertEquals(status, response.statusCode());
		assertEquals("application/json; charset=utf-8", response.headers().firstValue("Content-Type").orElse(""));
		assertEquals("no-store", response.headers().firstValue("Cache-Control").orElse(""));
		JsonNode body = ApiJson.MAPPER.readTree(response.body());
		assertEquals(1, body.path("api_version").intValue());
		assertEquals(code, body.at("/error/code").asText());
		assertFalse(body.at("/error/action_may_have_executed").booleanValue());
	}

	@Test public void requiresAuthenticationAndRejectsBrowserOrigins() throws Exception {
		assertError(request("/state").GET().build(), 401, "UNAUTHORIZED");
		assertError(request("/state").header("Authorization", "Bearer wrong").GET().build(), 401, "UNAUTHORIZED");
		assertError(request("/state").header("Authorization", "Bearer contract-test-token")
				.header("Origin", "https://example.org").GET().build(), 403, "BROWSER_ORIGIN");
	}

	@Test public void checksRoutesBodiesAndSizeBeforeEnqueueing() throws Exception {
		assertError(request("/unknown").header("Authorization", "Bearer contract-test-token")
				.GET().build(), 404, "NOT_FOUND");
		assertError(request("/action").header("Authorization", "Bearer contract-test-token")
				.GET().build(), 404, "NOT_FOUND");
		for (String body : new String[]{"{}", "null", "{\"type\":\"cell\",\"observation_id\":1}"}) {
			assertError(request("/action").header("Authorization", "Bearer contract-test-token")
					.POST(HttpRequest.BodyPublishers.ofString(body)).build(), 400, "INVALID_REQUEST");
		}
		assertError(request("/action").header("Authorization", "Bearer contract-test-token")
				.POST(HttpRequest.BodyPublishers.ofString("x".repeat(8193))).build(), 413, "REQUEST_TOO_LARGE");
	}
}
