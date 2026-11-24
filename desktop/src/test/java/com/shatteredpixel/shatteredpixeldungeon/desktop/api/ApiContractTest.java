package com.shatteredpixel.shatteredpixeldungeon.desktop.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.shatteredpixel.shatteredpixeldungeon.desktop.api.ApiModels.*;
import io.swagger.v3.core.util.Json;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.*;

public class ApiContractTest {
	private static final JsonNode SPEC = Json.mapper().valueToTree(GenerateOpenApi.specification());

	private static JsonSchema schema(String name) {
		ObjectNode root = ApiJson.MAPPER.createObjectNode();
		root.put("$ref", "#/components/schemas/" + name);
		root.set("components", SPEC.get("components"));
		return JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V7).getSchema(root);
	}

	static void assertConforms(String name, Object value) {
		assertEquals(Set.of(), schema(name).validate(ApiJson.MAPPER.valueToTree(value)));
	}

	private static Action parse(String json) throws Exception {
		return ApiJson.readAction(json.getBytes(StandardCharsets.UTF_8));
	}

	@Test public void everyActionParsesRoundTripsAndMatchesExactlyOneVariant() throws Exception {
		String[] bodies = {
				"\"type\":\"cell\",\"cell\":123", "\"type\":\"wait\"", "\"type\":\"search\"",
				"\"type\":\"inspect\",\"cell\":123", "\"type\":\"item\",\"id\":\"item-2\",\"action\":\"ZAP\"",
				"\"type\":\"button\",\"id\":\"button-3\"", "\"type\":\"text\",\"text\":\"ABC-DEF-GHI\"",
				"\"type\":\"back\"", "\"type\":\"talent\",\"name\":\"backup barrier\""
		};
		for (String body : bodies) {
			String json = "{\"observation_id\":1," + body + "}";
			Action action = parse(json);
			JsonNode serialized = ApiJson.MAPPER.readTree(ApiJson.MAPPER.writeValueAsBytes(action));
			assertEquals(ApiJson.MAPPER.readTree(json), serialized);
			assertEquals(json, Set.of(), schema("Action").validate(serialized));
			assertEquals(action.getClass(), parse(serialized.toString()).getClass());
		}
	}

	@Test public void malformedOrIncompleteActionsAreRejected() throws Exception {
		String[] bodies = {
				"null", "[]", "{}", "{\"type\":\"unknown\",\"observation_id\":1}",
				"{\"type\":\"wait\"}", "{\"type\":\"wait\",\"observation_id\":0}",
				"{\"type\":\"cell\",\"observation_id\":1}", "{\"type\":\"cell\",\"observation_id\":1,\"cell\":-1}",
				"{\"type\":\"item\",\"observation_id\":1,\"id\":\"item-0\"}",
				"{\"type\":\"text\",\"observation_id\":1,\"text\":null}",
				"{\"type\":\"wait\",\"observation_id\":\"1\"}",
				"{\"type\":\"wait\",\"observation_id\":1.5}",
				"{\"type\":\"wait\",\"observation_id\":1,\"extra\":true}",
				"{\"type\":\"wait\",\"observation_id\":1,\"observation_id\":2}",
				"{\"type\":\"wait\",\"observation_id\":1} {}",
				"{\"type\":\"text\",\"observation_id\":1,\"text\":\"" + "a".repeat(201) + "\"}"
		};
		for (String json : bodies) {
			try {
				parse(json);
				fail("Accepted " + json);
			} catch (java.io.IOException | IllegalArgumentException expected) {
				// Both syntax and wire-level constraints are checked before enqueueing.
			}
		}
	}

	@Test public void schemaRejectsMissingFieldsAndMismatchedActionTypes() throws Exception {
		for (String json : List.of(
				"{\"type\":\"cell\",\"observation_id\":1}",
				"{\"type\":\"wait\",\"observation_id\":1,\"cell\":123}",
				"{\"type\":\"unknown\",\"observation_id\":1}",
				"{\"type\":\"text\",\"observation_id\":1,\"text\":null}")) {
			assertFalse(schema("Action").validate(ApiJson.MAPPER.readTree(json)).isEmpty());
		}
	}

	private static Observation observation(String status) {
		Observation observation = new Observation();
		observation.observation_id = 1;
		observation.scene = status.equals("menu") ? "TitleScene" : "GameScene";
		observation.status = status;
		observation.capabilities.add("movement_trace");
		if (!status.equals("menu")) {
			observation.depth = 1;
			observation.branch = 0;
			observation.hero = new HeroState();
			observation.hero.name = "Mage";
			observation.hero.hero_class = "mage";
			observation.hero.subclass = "";
			observation.hero.armor_ability = "";
			observation.hero.hp = status.equals("dead") ? 0 : 20;
			observation.hero.max_hp = 20;
			observation.evaluation_stats = new EvaluationStats();
			observation.keys = new ArrayList<>();
			observation.inventory = new ArrayList<>();
			observation.map = new MapState();
			observation.objects = new ArrayList<>();
			observation.danger_cells = new ArrayList<>();
		}
		if (status.equals("targeting")) observation.target_prompt = "Choose a target";
		return observation;
	}

	@Test public void menuGameplayTargetingDeathAndMovementSerializeAgainstSchema() throws Exception {
		for (String status : List.of("menu", "ready", "targeting", "dead")) {
			Observation observation = observation(status);
			if (status.equals("ready")) {
				MovementTrace trace = new MovementTrace();
				trace.begin(1, 0, 32);
				trace.moved(33);
				observation.movement_trace = trace.finish(1, 0, 33);
				Tile tile = new Tile();
				tile.name = "floor";
				tile.cell = 33;
				tile.visible = true;
				tile.observed = true;
				tile.effects = List.of("toxic gas");
				observation.map.cells.add(tile);
				InventoryItem item = new InventoryItem();
				item.id = "item-0";
				item.name = "wand";
				item.description = "Unidentified wand";
				item.quantity = 1;
				item.actions.add("ZAP");
				observation.inventory.add(item);
				VisibleObject object = new VisibleObject();
				object.cell = 34;
				object.name = "rat";
				object.description = "A rat";
				object.health_bar = new int[]{3, 15};
				object.status_icons = List.of("Corruption");
				observation.objects.add(object);
			}
			JsonNode json = ApiJson.MAPPER.valueToTree(observation);
			assertEquals(status, Set.of(), schema("Observation").validate(json));
			assertEquals(1, json.get("api_version").intValue());
			assertFalse(json.has("boss_health_text"));
			assertFalse(json.has("class"));
			assertEquals(!status.equals("menu"), json.has("hero"));
			assertEquals(status.equals("targeting"), json.has("target_prompt"));
		}
	}

	@Test public void responseModelsContainOnlyWireTypes() {
		for (Class<?> model : ApiModels.class.getDeclaredClasses()) {
			if (model.isEnum()) continue;
			for (java.lang.reflect.Field field : model.getFields()) {
				if (java.lang.reflect.Modifier.isStatic(field.getModifiers())) continue;
				Class<?> type = field.getType();
				assertTrue(field.toString(), type.isPrimitive() || type == String.class
						|| type == Integer.class || type == Long.class || type == Boolean.class
						|| type == int[].class || type == float[].class || type.getEnclosingClass() == ApiModels.class
						|| (type == List.class && field.getGenericType().getTypeName()
						.matches("java.util.List<(java.lang.(String|Integer)|.*ApiModels\\$[A-Za-z]+)>")));
			}
		}
	}

	@Test public void everyStructuredErrorConforms() {
		for (ErrorCode code : ErrorCode.values()) {
			ErrorResponse error = new ErrorResponse(code, "Test message", code == ErrorCode.DECISION_TIMEOUT);
			assertEquals(Set.of(), schema("ErrorResponse").validate(ApiJson.MAPPER.valueToTree(error)));
		}
	}

	@Test public void endpointsSecurityAndTransportDetailsArePresent() {
		assertEquals(2, SPEC.get("paths").size());
		JsonNode state = SPEC.at("/paths/~1state/get");
		JsonNode step = SPEC.at("/paths/~1action/post");
		assertEquals("state", state.get("operationId").asText());
		assertEquals("step", step.get("operationId").asText());
		assertTrue(step.at("/requestBody/required").booleanValue());
		assertTrue(step.at("/security/0").has("bearerAuth"));
		assertEquals("#/components/schemas/Observation",
				state.at("/responses/200/content/application~1json/schema/$ref").asText());
		assertTrue(step.at("/responses/504/description").asText().contains("never blindly retry"));
		assertEquals(9, SPEC.at("/components/schemas/Action/oneOf").size());
	}

	@Test public void jacksonSubtypeRegistryMatchesDocumentedActions() {
		com.fasterxml.jackson.annotation.JsonSubTypes registry =
				Action.class.getAnnotation(com.fasterxml.jackson.annotation.JsonSubTypes.class);
		JsonNode mapping = SPEC.at("/components/schemas/Action/discriminator/mapping");
		assertEquals(registry.value().length, mapping.size());
		for (com.fasterxml.jackson.annotation.JsonSubTypes.Type type : registry.value()) {
			assertEquals("#/components/schemas/" + type.value().getSimpleName(),
					mapping.path(type.name()).asText());
		}
	}
}
