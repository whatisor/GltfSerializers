package org.bimserver.gltf;

/******************************************************************************
 * Copyright (C) 2009-2018  BIMserver.org
 * 
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 * 
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see {@literal<http://www.gnu.org/licenses/>}.
 *****************************************************************************/

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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class BinaryGltfSerializerPlugin2 extends AbstractSerializerPlugin {

	private byte[] vertexColorFragmentShaderBytes;
	private byte[] vertexColorVertexShaderBytes;
	private byte[] materialColorFragmentShaderBytes;
	private byte[] materialColorVertexShaderBytes;

	final Logger LOGGER = LoggerFactory.getLogger(BinaryGltfSerializerPlugin2.class);
	@Override
	public void init(PluginContext pluginContext) throws PluginException {
		Path vertexColorFragmentShaderPath = pluginContext.getRootPath().resolve("shaders/fragmentcolor.shader");
		Path vertexColorVertexShaderPath = pluginContext.getRootPath().resolve("shaders/vertexcolor.shader");
		Path materialColorFragmentShaderPath = pluginContext.getRootPath().resolve("shaders/fragmentmaterial.shader");
		Path materialColorVertexShaderPath = pluginContext.getRootPath().resolve("shaders/vertexmaterial.shader");
		LOGGER.info("GLTFPlugin2 Init");
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
		LOGGER.info("GLTFPlugin2 "+new Object(){}.getClass().getEnclosingMethod().getName()+plugin.toString());
		//LOGGER.info("GLTFPlugin2 testParam "+plugin.getString("testParam"));
		return new BinaryGltfSerializer2(vertexColorFragmentShaderBytes, vertexColorVertexShaderBytes, materialColorFragmentShaderBytes, materialColorVertexShaderBytes);
	}

	@Override
	public Set<Schema> getSupportedSchemas() {
		LOGGER.info("GLTFPlugin2 "+new Object(){}.getClass().getEnclosingMethod().getName());
		return Collections.singleton(Schema.IFC2X3TC1);
	}

	@Override
	public boolean needsGeometry() {
		LOGGER.info("GLTFPlugin2 "+new Object(){}.getClass().getEnclosingMethod().getName());
		
		return true;
	}

	@Override
	public String getDefaultExtension() {
		LOGGER.info("GLTFPlugin2 "+new Object(){}.getClass().getEnclosingMethod().getName());
		
		return "glb";
	}

	@Override
	public String getDefaultContentType() {
		LOGGER.info("GLTFPlugin2 "+new Object(){}.getClass().getEnclosingMethod().getName());
		
		return "model/gltf-binary";
	}

	@Override
	public String getOutputFormat(Schema schema) {
		LOGGER.info("GLTFPlugin2 "+new Object(){}.getClass().getEnclosingMethod().getName());
		
		return SchemaName.GLTF_BIN_2_0.name();
	}
}