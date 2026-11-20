package com.shatteredpixel.shatteredpixeldungeon.desktop.api;

import io.swagger.v3.core.util.Json;
import io.swagger.v3.core.util.Yaml;
import io.swagger.v3.jaxrs2.Reader;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.servers.Server;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

/** Build-time only. Reads metadata without constructing a game, HTTP server, or renderer. */
public final class GenerateOpenApi {
	private GenerateOpenApi() {}

	public static OpenAPI specification() {
		// Swagger respects the Jackson field/subtype annotations used by the wire serializer.
		OpenAPI api = new Reader(new OpenAPI()).read(PlayerApi.class);
		normalizeActions(api);
		// Reader inspects the transport future in addition to the explicitly annotated wire response.
		api.getComponents().getSchemas().remove("Response");
		api.getComponents().getSchemas().values().stream()
				.filter(schema -> "object".equals(schema.getType()))
				.forEach(schema -> schema.setAdditionalProperties(false));
		api.addServersItem(new Server().url("http://127.0.0.1:8765")
				.description("Example loopback address; port is configured with SPD_API_PORT."));
		return api;
	}

	@SuppressWarnings({"rawtypes", "unchecked"})
	private static void normalizeActions(OpenAPI api) {
		Map<String, Schema> schemas = api.getComponents().getSchemas();
		Schema action = schemas.get("Action");
		// Swagger expresses Java inheritance as Action -> oneOf variant -> allOf Action,
		// which is circular in JSON Schema. Flatten the variants using the generated
		// discriminator mapping, and constrain each type to its actual Jackson subtype ID.
		action.getDiscriminator().getMapping().forEach((name, reference) -> {
			Schema variant = schemas.get(reference.substring(reference.lastIndexOf('/') + 1));
			Map<String, Schema> properties = new LinkedHashMap<>(action.getProperties());
			if (variant.getAllOf() != null) {
				for (Object value : variant.getAllOf()) {
					Schema part = (Schema) value;
					if (part.getProperties() != null) properties.putAll(part.getProperties());
				}
			}
			if (variant.getProperties() != null) properties.putAll(variant.getProperties());
			properties.put("type", new StringSchema()._enum(java.util.List.of(name)));
			variant.setAllOf(null);
			variant.setProperties(properties);
			ArrayList<String> required = new ArrayList<>(action.getRequired());
			if (variant.getRequired() != null) {
				for (Object field : variant.getRequired()) {
					if (!required.contains(field)) required.add((String) field);
				}
			}
			variant.setRequired(required);
			variant.setAdditionalProperties(false);
		});
		// The common fields now live in each concrete variant; keep the root a pure union.
		action.setProperties(null);
		action.setRequired(null);
		action.setType(null);
	}

	public static void main(String[] args) throws Exception {
		Path directory = Path.of(args[0]);
		Files.createDirectories(directory);
		OpenAPI api = specification();
		Files.writeString(directory.resolve("openapi.json"), Json.pretty(api) + "\n");
		Files.writeString(directory.resolve("openapi.yaml"), Yaml.pretty(api));
	}
}
