package org.bimserver.gltf;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Set;

import org.bimserver.emf.Schema;
import org.bimserver.plugins.PluginConfiguration;
import org.bimserver.plugins.PluginContext;
import org.bimserver.plugins.SchemaName;
import org.bimserver.plugins.serializers.AbstractSerializerPlugin;
import org.bimserver.plugins.serializers.Serializer;
import org.bimserver.shared.exceptions.PluginException;

public class BinaryGltfSerializerPlugin extends AbstractSerializerPlugin {

	private byte[] vertexColorFragmentShaderBytes;
	private byte[] vertexColorVertexShaderBytes;
	private byte[] materialColorFragmentShaderBytes;
	private byte[] materialColorVertexShaderBytes;

	@Override
	public void init(PluginContext pluginContext) throws PluginException {
		Path vertexColorFragmentShaderPath = pluginContext.getRootPath().resolve("shaders/fragmentcolor.shader");
		Path vertexColorVertexShaderPath = pluginContext.getRootPath().resolve("shaders/vertexcolor.shader");
		Path materialColorFragmentShaderPath = pluginContext.getRootPath().resolve("shaders/fragmentmaterial.shader");
		Path materialColorVertexShaderPath = pluginContext.getRootPath().resolve("shaders/vertexmaterial.shader");
		
		try {
			vertexColorFragmentShaderBytes = Files.readAllBytes(vertexColorFragmentShaderPath);
			vertexColorVertexShaderBytes = Files.readAllBytes(vertexColorVertexShaderPath);
			materialColorFragmentShaderBytes = Files.readAllBytes(materialColorFragmentShaderPath);
			materialColorVertexShaderBytes = Files.readAllBytes(materialColorVertexShaderPath);
		} catch (IOException e) {
			throw new PluginException(e);
		}
	}

	@Override
	public Serializer createSerializer(PluginConfiguration plugin) {
		return new BinaryGltfSerializer(vertexColorFragmentShaderBytes, vertexColorVertexShaderBytes, materialColorFragmentShaderBytes, materialColorVertexShaderBytes);
	}

	@Override
	public Set<Schema> getSupportedSchemas() {
		return Collections.singleton(Schema.IFC2X3TC1);
	}

	@Override
	public boolean needsGeometry() {
		return true;
	}

	@Override
	public String getDefaultExtension() {
		return "glb";
	}

	@Override
	public String getDefaultContentType() {
		return "model/gltf+binary";
	}

	@Override
	public String getOutputFormat(Schema schema) {
		return SchemaName.GLTF_BIN_1_0.name();
	}
}