package org.bimserver.gltf;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import org.bimserver.emf.Schema;
import org.bimserver.plugins.PluginConfiguration;
import org.bimserver.plugins.PluginContext;
import org.bimserver.plugins.SchemaName;
import org.bimserver.plugins.serializers.AbstractSerializerPlugin;
import org.bimserver.plugins.serializers.Serializer;
import org.bimserver.shared.exceptions.PluginException;

public class BinaryGltfSerializerPlugin2 extends AbstractSerializerPlugin {

	@Override
	public void init(PluginContext pluginContext, PluginConfiguration systemSettings) throws PluginException {
	}

	@Override
	public Serializer createSerializer(PluginConfiguration plugin) {
		return new BinaryGltfSerializer2();
	}

	@Override
	public Set<Schema> getSupportedSchemas() {
		return Collections.singleton(Schema.IFC2X3TC1);
	}

	@Override
	public Set<String> getRequiredGeometryFields() {
		Set<String> set = new HashSet<>();
		set.add("indices");
		set.add("vertices");
		set.add("normals");
		set.add("colorsQuantized");
		return set;
	}

	@Override
	public String getDefaultExtension() {
		return "glb";
	}

	@Override
	public String getDefaultContentType() {
		return "model/gltf-binary";
	}

	@Override
	public String getOutputFormat(Schema schema) {
		return SchemaName.GLTF_BIN_2_0.name();
	}
}