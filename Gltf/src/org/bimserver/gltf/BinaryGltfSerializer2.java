package org.bimserver.gltf;

/******************************************************************************
 * Copyright (C) 2009-2019  BIMserver.org
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
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.DoubleBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import org.antlr.tool.BuildDependencyGenerator;
import org.bimserver.emf.IdEObject;
import org.bimserver.geometry.IfcColors;
import org.bimserver.geometry.Matrix;
import org.bimserver.models.geometry.GeometryData;
import org.bimserver.models.geometry.GeometryInfo;
import org.bimserver.models.geometry.Vector4f;
import org.bimserver.models.ifc2x3tc1.IfcBuilding;
import org.bimserver.models.ifc2x3tc1.IfcBuildingElement;
import org.bimserver.models.ifc2x3tc1.IfcBuildingStorey;
import org.bimserver.models.ifc2x3tc1.IfcElement;
import org.bimserver.models.ifc2x3tc1.IfcObjectDefinition;
import org.bimserver.models.ifc2x3tc1.IfcObjectPlacement;
import org.bimserver.models.ifc2x3tc1.IfcProduct;
import org.bimserver.models.ifc2x3tc1.IfcRelAggregates;
import org.bimserver.models.ifc2x3tc1.IfcRelAssignsToProduct;
import org.bimserver.models.ifc2x3tc1.IfcRelConnectsElements;
import org.bimserver.models.ifc2x3tc1.IfcRelConnectsPortToElement;
import org.bimserver.models.ifc2x3tc1.IfcRelConnectsStructuralElement;
import org.bimserver.models.ifc2x3tc1.IfcRelConnectsWithRealizingElements;
import org.bimserver.models.ifc2x3tc1.IfcRelContainedInSpatialStructure;
import org.bimserver.models.ifc2x3tc1.IfcRelCoversBldgElements;
import org.bimserver.models.ifc2x3tc1.IfcRelDecomposes;
import org.bimserver.models.ifc2x3tc1.IfcRelDefines;
import org.bimserver.models.ifc2x3tc1.IfcRelFillsElement;
import org.bimserver.models.ifc2x3tc1.IfcRelProjectsElement;
import org.bimserver.models.ifc2x3tc1.IfcRelReferencedInSpatialStructure;
import org.bimserver.models.ifc2x3tc1.IfcRelSpaceBoundary;
import org.bimserver.models.ifc2x3tc1.IfcRelVoidsElement;
import org.bimserver.models.ifc2x3tc1.IfcSpace;
import org.bimserver.models.ifc2x3tc1.IfcSpatialStructureElement;
import org.bimserver.models.store.ObjectDefinition;
import org.bimserver.models.ifc2x3tc1.IfcSite;
import org.bimserver.plugins.serializers.EmfSerializer;
import org.bimserver.plugins.serializers.ProgressReporter;
import org.bimserver.plugins.serializers.SerializerException;
import org.eclipse.emf.common.util.BasicEList;
import org.eclipse.emf.common.util.EList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.common.base.Charsets;
import com.google.common.io.LittleEndianDataOutputStream;
import com.google.common.primitives.UnsignedBytes;

/**
 * @author Ruben de Laat
 *
 *         Annoying things about glTF so far: - Total and scene length have to
 *         be computed in advance, so no streaming is possible
 *
 */
public class BinaryGltfSerializer2 extends BinaryGltfBaseSerializer {

	private static final Logger LOGGER1 = LoggerFactory.getLogger(BinaryGltfSerializer2.class);
	private static final int ARRAY_BUFFER = 34962;
	private static final int ELEMENT_ARRAY_BUFFER = 34963;
	private static final int MAGIC = 0x46546C67;
	private static final int UNSIGNED_INT = 5125;
	private static final int TRIANGLES = 4;
	private static final int FLOAT = 5126;
	private static final int UNSIGNED_BYTE = 5121;
	private static final int JSON_CHUNK = 0x4E4F534A;
	private static final int BINARY_CHUNK = 0x004E4942;
	private static final int FORMAT_VERSION = 2;
	private static final String GLTF_VERSION = "2.0";

	private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
	private ArrayNode buffers;
	private ByteBuffer body;
	private ArrayNode meshes;
	private ArrayNode accessors;
	private ArrayNode buffersViews;
	private ArrayNode defaultSceneNodes;
	private ArrayNode scenesNode;
	private ObjectNode gltfNode;
	private ArrayNode nodes;

	double[] min = { Double.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE };
	double[] max = { -Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE };
	private ArrayNode modelTranslation;
	private ArrayNode materials;

	private final Map<String, Integer> createdMaterials = new HashMap<>();
	private ArrayNode translationChildrenNode;
	private int vertexColorIndex;

	enum ControlMode {
		METADATA, PART, FULL
	}

	private ControlMode controlMode = ControlMode.METADATA;
	// private List<String> partIDs = new ArrayList<String>();
	LinkedHashMap<String, Boolean> partIDsMap = new LinkedHashMap<String, Boolean>();
	String selectedPart = "";
	boolean isTreeOnly = true;

	public void setLoadMode(boolean isTreeOnly) {
		this.isTreeOnly = isTreeOnly;
	}

	public BinaryGltfSerializer2() {
	}

	@Override
	protected boolean write(OutputStream outputStream, ProgressReporter progressReporter) throws SerializerException {
		LOGGER1.info("Starting serialization");
		String systemDir = System.getProperty("java.io.tmpdir");
		LOGGER1.info("System dir " + systemDir);
		isTreeOnly = false;
		autoUnknown = 1;
		partIDsMap.clear();
		// metadata
		if (Files.exists(Paths.get(systemDir + "/" + "metadata.gltfOPT"))) {
			controlMode = ControlMode.METADATA;
			isTreeOnly = true;
			// String content = new
			// String(Files.readAllBytes(Paths.get(systemDir+"/"+"metadata.gltfOPT")));
			try {
				Files.delete(Paths.get(systemDir + "/" + "metadata.gltfOPT"));
			} catch (IOException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
		} else if (Files.exists(Paths.get(systemDir + "/" + "full.gltfOPT"))) {
			controlMode = ControlMode.FULL;
			try {
				Files.delete(Paths.get(systemDir + "/" + "full.gltfOPT"));
			} catch (IOException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
		} else if (Files.exists(Paths.get(systemDir + "/" + "part.gltfOPT"))) {
			try {
				controlMode = ControlMode.PART;
				String content = new String(Files.readAllBytes(Paths.get(systemDir + "/" + "part.gltfOPT")));
				System.out.println("part ids " + content);
				String[] ids = content.split(",");
				// partIDs = Arrays.asList(ids);
				for (String id : ids) {
					partIDsMap.put(id.replace("_", " "), true);
				}
				selectedPart = partIDsMap.entrySet().iterator().next().getKey();
				if (partIDsMap.containsKey("TranslationPivot") || partIDsMap.containsKey("RotationPivot")) {

					controlMode = ControlMode.FULL;
				}
				try {
					Files.delete(Paths.get(systemDir + "/" + "part.gltfOPT"));
				} catch (IOException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				}
			} catch (IOException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
		} else {

			controlMode = ControlMode.FULL;
		}

		LOGGER1.info("Control mode " + controlMode.toString());

		gltfNode = OBJECT_MAPPER.createObjectNode();

		buffers = OBJECT_MAPPER.createArrayNode();
		meshes = OBJECT_MAPPER.createArrayNode();
		buffersViews = OBJECT_MAPPER.createArrayNode();
		scenesNode = OBJECT_MAPPER.createArrayNode();
		accessors = OBJECT_MAPPER.createArrayNode();
		nodes = OBJECT_MAPPER.createArrayNode();
		materials = OBJECT_MAPPER.createArrayNode();

		gltfNode.set("meshes", meshes);
		gltfNode.set("bufferViews", buffersViews);
		gltfNode.set("scenes", scenesNode);
		gltfNode.set("accessors", accessors);
		gltfNode.set("nodes", nodes);
		gltfNode.set("buffers", buffers);
		gltfNode.set("materials", materials);

		createVertexColorMaterial();

		try {
			LittleEndianDataOutputStream dataOutputStream = new LittleEndianDataOutputStream(outputStream);

			generateSceneAndBody();

//			StringWriter stringWriter = new StringWriter();
//			OBJECT_MAPPER.writerWithDefaultPrettyPrinter().writeValue(stringWriter, gltfNode);
//			System.out.println(stringWriter);

			byte[] sceneBytes = gltfNode.toString().getBytes(Charsets.UTF_8);
			int sceneLength = sceneBytes.length + (sceneBytes.length % 4 == 0 ? 0 : 4 - sceneBytes.length % 4);
			int bodyLength = body.capacity() + (body.capacity() % 4 == 0 ? 0 : 4 - body.capacity() % 4);
			writeHeader(dataOutputStream, 12, 8 + sceneLength, 8 + bodyLength);
			writeScene(dataOutputStream, sceneBytes);

			if (!isTreeOnly) {
				writeBody(dataOutputStream, body.array());
			}
			dataOutputStream.flush();
		} catch (Exception e) {
			throw new SerializerException(e);
		}
		return false;
	}

	private void createModelNode() {
		ObjectNode translationNode = OBJECT_MAPPER.createObjectNode();
		modelTranslation = OBJECT_MAPPER.createArrayNode();

		translationChildrenNode = OBJECT_MAPPER.createArrayNode();
		translationNode.set("children", translationChildrenNode);
		translationNode.set("translation", modelTranslation);
		translationNode.put("name", "TranslationPivot");
		ObjectNode rotationNode = OBJECT_MAPPER.createObjectNode();

		ArrayNode rotation = OBJECT_MAPPER.createArrayNode();
		ArrayNode rotationChildrenNode = OBJECT_MAPPER.createArrayNode();

		nodes.add(translationNode);

		rotationChildrenNode.add(nodes.size() - 1);
		rotationNode.set("children", rotationChildrenNode);
		rotationNode.set("rotation", rotation);
		rotationNode.put("name", "RotationPivot");

		float[] quat = normalizeQuaternion(new float[] { 1, 0, 0, -1f });

		rotation.add(quat[0]);
		rotation.add(quat[1]);
		rotation.add(quat[2]);
		rotation.add(quat[3]);

		nodes.add(rotationNode);
		defaultSceneNodes.add(nodes.size() - 1);
	}

	public float len2(float[] input) {
		return input[0] * input[0] + input[1] * input[1] + input[2] * input[2] + input[3] * input[3];
	}

	private float[] normalizeQuaternion(float[] input) {
		float len = len2(input);
		if (len != 0.f && len != 1f) {
			len = (float) Math.sqrt(len);
			input[0] /= len;
			input[1] /= len;
			input[2] /= len;
			input[3] /= len;
		}
		return input;
	}

	private void generateSceneAndBody() throws SerializerException {
		typedProductFromModel.clear();
		typedProductDebug.clear();
		totalColorsByteLength = 0;
		totalBodyByteLength = 0;
		totalIndicesByteLength = 0;
		totalVerticesByteLength = 0;
		totalNormalsByteLength = 0;
		colorsBufferView = -1;

		LOGGER1.info("generteSceneAndBody");
		listProduct.clear();
		state = 0;
		buildGeometry();
//		List<IfcProduct> test = model.getAllWithSubTypes(IfcProduct.class);
		List<IfcProduct> products = listProduct;// model.getAllWithSubTypes(IfcProduct.class);
		for (IfcProduct ifcProduct : products) {
			String type = ifcProduct.eClass().getName();
			int size = calculateBuffer(ifcProduct);
			if (size > 0) {
				if (!typedProductFromModel.containsKey(type)) {
					typedProductFromModel.put(type, 0);
				}
				typedProductFromModel.replace(type, typedProductFromModel.get(type) + 1);
			}
		}
		if (totalIndicesByteLength == 0) {
			throw new SerializerException("No geometry");
		}
		totalBodyByteLength = totalIndicesByteLength + totalVerticesByteLength + totalNormalsByteLength
				+ totalColorsByteLength;

		body = ByteBuffer.allocate(totalBodyByteLength);
		body.order(ByteOrder.LITTLE_ENDIAN);

		newIndicesBuffer = ByteBuffer.allocate(totalIndicesByteLength);
		newIndicesBuffer.order(ByteOrder.LITTLE_ENDIAN);

		newVerticesBuffer = ByteBuffer.allocate(totalVerticesByteLength);
		newVerticesBuffer.order(ByteOrder.LITTLE_ENDIAN);

		newNormalsBuffer = ByteBuffer.allocate(totalNormalsByteLength);
		newNormalsBuffer.order(ByteOrder.LITTLE_ENDIAN);

		newColorsBuffer = ByteBuffer.allocate(totalColorsByteLength);
		newColorsBuffer.order(ByteOrder.LITTLE_ENDIAN);

		indicesBufferView = createBufferView(totalIndicesByteLength, 0, ELEMENT_ARRAY_BUFFER, -1);
		verticesBufferView = createBufferView(totalVerticesByteLength, totalIndicesByteLength, ARRAY_BUFFER, 12);
		normalsBufferView = createBufferView(totalNormalsByteLength, totalIndicesByteLength + totalVerticesByteLength,
				ARRAY_BUFFER, 12);
		colorsBufferView = -1;

		scenesNode.add(createDefaultScene());
		gltfNode.put("scene", 0);
		createModelNode();

		for (IfcProduct ifcProduct : products) {
			updateMatrix(ifcProduct);
		}

		double[] offsets = getOffsets();

		// This will "normalize" the model by moving it's axis-aligned bounding
		// box center to the 0-point. This will always be the wrong position,
		// but at least the building will be close to the 0-point
		modelTranslation.add(-offsets[0]);
		modelTranslation.add(-offsets[1]);
		modelTranslation.add(-offsets[2]);

		// analysis tree
		state = 1;
		buildGeometry();
		if (newIndicesBuffer.position() != newIndicesBuffer.capacity()) {
			throw new SerializerException("Not all space used");
		}
		if (newVerticesBuffer.position() != newVerticesBuffer.capacity()) {
			throw new SerializerException("Not all space used");
		}
		if (newNormalsBuffer.position() != newNormalsBuffer.capacity()) {
			throw new SerializerException("Not all space used");
		}
		if (newColorsBuffer.position() != newColorsBuffer.capacity()) {
			throw new SerializerException("Not all space used");
		}

		newIndicesBuffer.position(0);
		newVerticesBuffer.position(0);
		newNormalsBuffer.position(0);
		newColorsBuffer.position(0);

		body.put(newIndicesBuffer);
		body.put(newVerticesBuffer);
		body.put(newNormalsBuffer);
		body.put(newColorsBuffer);

		gltfNode.set("asset", createAsset());
		gltfNode.put("scene", 0);

		addBuffer(body.capacity());
	}

	private double[] getOffsets() {
		double[] changes = new double[3];
		for (int i = 0; i < 3; i++) {
			changes[i] = (max[i] - min[i]) / 2.0f + min[i];
		}
		return changes;
	}

	private void updateExtends(GeometryInfo geometryInfo, double[] matrix) {
		if (geometryInfo.getData() == null || geometryInfo.getData().getVertices() == null) {
			return;
		}
		ByteBuffer verticesByteBufferBuffer = ByteBuffer.wrap(geometryInfo.getData().getVertices().getData());
		verticesByteBufferBuffer.order(ByteOrder.LITTLE_ENDIAN);
		DoubleBuffer doubleBuffer = verticesByteBufferBuffer.asDoubleBuffer();
		for (int i = 0; i < doubleBuffer.capacity(); i += 3) {
			double[] input = new double[] { doubleBuffer.get(i), doubleBuffer.get(i + 1), doubleBuffer.get(i + 2), 1 };
			double[] output = new double[4];
			Matrix.multiplyMV(output, 0, matrix, 0, input, 0);
			for (int j = 0; j < 3; j++) {
				double value = output[j];
				if (value > max[j]) {
					max[j] = value;
				}
				if (value < min[j]) {
					min[j] = value;
				}
			}
		}
	}

	private int addNode(int meshId, IfcProduct ifcProduct) {
		ObjectNode nodeNode = OBJECT_MAPPER.createObjectNode();

		ArrayNode matrixArray = OBJECT_MAPPER.createArrayNode();
		ByteBuffer matrixByteBuffer = ByteBuffer.wrap(ifcProduct.getGeometry().getTransformation());
		matrixByteBuffer.order(ByteOrder.LITTLE_ENDIAN);
		DoubleBuffer doubleBuffer = matrixByteBuffer.asDoubleBuffer();
		double[] buffer = new double[16];
		for (int i = 0; i < 16; i++) {
			double d = doubleBuffer.get(i);
			matrixArray.add(d);
			buffer[i] = d;
		}
		String GUID = ifcProduct.getGlobalId();
		ObjectNode extrasNode = OBJECT_MAPPER.createObjectNode();
		nodeNode.set("extras", extrasNode);
		extrasNode.put("ifcID", GUID);
		nodeNode.put("mesh", meshId);
		if (!Matrix.isIdentity(buffer)) {
			if (!Matrix.invertM(new double[16], 0, buffer, 0)) {
				LOGGER1.info("Could not invert matrix, omitting");
				// Cannot be inverted, we know cesium at least doesn't like that
			} else {
				nodeNode.set("matrix", matrixArray);
			}
		}
		nodes.add(nodeNode);

		return nodes.size() - 1;
	}

	private ObjectNode createDefaultScene() {
		ObjectNode sceneNode = OBJECT_MAPPER.createObjectNode();

		defaultSceneNodes = OBJECT_MAPPER.createArrayNode();

		sceneNode.set("nodes", defaultSceneNodes);
		sceneNode.put("name", "Scene");

		return sceneNode;
	}

	private int createBufferView(int byteLength, int byteOffset, int target, int byteStride) {
		ObjectNode bufferViewNode = OBJECT_MAPPER.createObjectNode();

		bufferViewNode.put("buffer", 0);
		bufferViewNode.put("byteLength", byteLength);
		bufferViewNode.put("byteOffset", byteOffset);
		if (byteStride != -1) {
			bufferViewNode.put("byteStride", byteStride);
		}
		if (target != -1) {
			bufferViewNode.put("target", target);
		}

		buffersViews.add(bufferViewNode);

		return buffersViews.size() - 1;
	}

	private int addNormalsAccessor(IfcProduct ifcProduct, int bufferViewIndex, int byteOffset, int count)
			throws SerializerException {
		if (count <= 0) {
			throw new SerializerException("Count <= 0");
		}

		ObjectNode accessor = OBJECT_MAPPER.createObjectNode();
		accessor.put("bufferView", bufferViewIndex);
		accessor.put("byteOffset", byteOffset);
//		accessor.put("byteStride", 12);
		accessor.put("componentType", FLOAT);
		accessor.put("count", count);
		accessor.put("type", "VEC3");

		ArrayNode min = OBJECT_MAPPER.createArrayNode();
		min.add(-1d);
		min.add(-1d);
		min.add(-1d);
		ArrayNode max = OBJECT_MAPPER.createArrayNode();
		max.add(1);
		max.add(1);
		max.add(1);

//		accessor.set("min", min);
//		accessor.set("max", max);

		accessors.add(accessor);

		return accessors.size() - 1;
	}

	private int addColorsAccessor(IfcProduct ifcProduct, int bufferViewIndex, int byteOffset, int count) {
		ObjectNode accessor = OBJECT_MAPPER.createObjectNode();
		accessor.put("bufferView", bufferViewIndex);
		accessor.put("byteOffset", byteOffset);
//		accessor.put("byteStride", 16);
		accessor.put("componentType", UNSIGNED_BYTE);
		accessor.put("normalized", true);
		accessor.put("count", count);
		accessor.put("type", "VEC4");

//		ArrayNode min = OBJECT_MAPPER.createArrayNode();
//		min.add(-1d);
//		min.add(-1d);
//		min.add(-1d);
//		ArrayNode max = OBJECT_MAPPER.createArrayNode();
//		max.add(1);
//		max.add(1);
//		max.add(1);
//		
//		accessor.set("min", min);
//		accessor.set("max", max);

		accessors.add(accessor);

		return accessors.size() - 1;
	}

	private int addVerticesAccessor(IfcProduct ifcProduct, int bufferViewIndex, int startPosition, int count)
			throws SerializerException {
		if (count <= 0) {
			throw new SerializerException("Count <= 0");
		}
		GeometryData data = ifcProduct.getGeometry().getData();
		ByteBuffer verticesBuffer = ByteBuffer.wrap(data.getVertices().getData());

		ObjectNode accessor = OBJECT_MAPPER.createObjectNode();
		accessor.put("bufferView", bufferViewIndex);
		accessor.put("byteOffset", startPosition);
//		accessor.put("byteStride", 12);
		accessor.put("componentType", FLOAT);
		accessor.put("count", count);
		accessor.put("type", "VEC3");

		verticesBuffer.order(ByteOrder.LITTLE_ENDIAN);
		double[] min = { Double.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE };
		double[] max = { -Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE };
		for (int i = 0; i < verticesBuffer.capacity(); i += 24) {
			for (int j = 0; j < 3; j++) {
				double val = verticesBuffer.getDouble(i + (j * 8));
				if (val > max[j]) {
					max[j] = val;
				}
				if (val < min[j]) {
					min[j] = val;
				}
			}
		}

		ArrayNode minNode = OBJECT_MAPPER.createArrayNode();
		minNode.add(min[0]);
		minNode.add(min[1]);
		minNode.add(min[2]);
		ArrayNode maxNode = OBJECT_MAPPER.createArrayNode();
		maxNode.add(max[0]);
		maxNode.add(max[1]);
		maxNode.add(max[2]);

		accessor.set("min", minNode);
		accessor.set("max", maxNode);

		accessors.add(accessor);

		return accessors.size() - 1;
	}

	private int addIndicesAccessor(IfcProduct ifcProduct, int bufferViewIndex, int offsetBytes, int count, int[] min,
			int[] max) throws SerializerException {
		if (count <= 0) {
			throw new SerializerException(count + " <= 0");
		}

		ObjectNode accessor = OBJECT_MAPPER.createObjectNode();
		accessor.put("bufferView", bufferViewIndex);
		accessor.put("byteOffset", offsetBytes);
//		accessor.put("byteStride", 0);
		accessor.put("componentType", UNSIGNED_INT);
		accessor.put("count", count);
		accessor.put("type", "SCALAR");

//		ArrayNode minArray = OBJECT_MAPPER.createArrayNode();
//		ArrayNode maxArray = OBJECT_MAPPER.createArrayNode();
//		
//		for (int i=0; i<min.length; i++) {
//			minArray.add(min[i]);
//			maxArray.add(max[i]);
//		}
//		
//		accessor.set("min", minArray);
//		accessor.set("max", maxArray);

		accessors.add(accessor);

		return accessors.size() - 1;
	}

	final ObjectMapper mapper = new ObjectMapper();

	private int addMesh(IfcProduct ifcProduct, ArrayNode primitivesNode) {
		ObjectNode meshNode = OBJECT_MAPPER.createObjectNode();
		String name = ifcProduct.getName();
		if (name == null)
			name = "Unknown" + autoUnknown++;
		meshNode.set("name", mapper.convertValue(name, JsonNode.class));
		meshNode.set("primitives", primitivesNode);
		meshes.add(meshNode);
		return meshes.size() - 1;
	}

	private void addBuffer(int byteLength) {
		ObjectNode bufferNode = OBJECT_MAPPER.createObjectNode();

		bufferNode.put("byteLength", byteLength);
//		bufferNode.put("uri", "data:,");

		buffers.add(bufferNode);
	}

	private int createOrGetMaterial(String name, float[] colors) {
		if (createdMaterials.containsKey(name)) {
			return createdMaterials.get(name);
		}
		ObjectNode material = OBJECT_MAPPER.createObjectNode();

		material.put("name", name + "Material");
//		material.put("technique", "materialColorTechnique");

		ObjectNode values = OBJECT_MAPPER.createObjectNode();

		ArrayNode diffuse = OBJECT_MAPPER.createArrayNode();
		for (int i = 0; i < 4; i++) {
			diffuse.add(colors[i]);
		}
//		diffuse.add(0.8000000119209291);
//		diffuse.add(0);
//		diffuse.add(0);
//		diffuse.add(1);

		ArrayNode specular = OBJECT_MAPPER.createArrayNode();
		specular.add(0.20000000298023218);
		specular.add(0.20000000298023218);
		specular.add(0.20000000298023218);

		values.set("diffuse", diffuse);
		values.set("specular", specular);
		values.put("shininess", 256);

//		material.set("values", values);

		materials.add(material);

		createdMaterials.put(name, materials.size() - 1);

		return materials.size() - 1;
	}

	private void createVertexColorMaterial() {
		ObjectNode defaultMaterial = OBJECT_MAPPER.createObjectNode();

//		defaultMaterial.put("technique", "vertexColorTechnique");

//		ObjectNode values = OBJECT_MAPPER.createObjectNode();

//		defaultMaterial.set("values", values);

		materials.add(defaultMaterial);
		vertexColorIndex = materials.size() - 1;
	}

	private JsonNode createAsset() {
		ObjectNode asset = OBJECT_MAPPER.createObjectNode();
		asset.put("version", GLTF_VERSION);
		// TODO add the optional fields
		return asset;
	}

	private void writeHeader(LittleEndianDataOutputStream dataOutputStream, int headerLength, int sceneLength,
			int bodyLength) throws IOException {
		dataOutputStream.writeInt(MAGIC);
		dataOutputStream.writeInt(FORMAT_VERSION);
		dataOutputStream.writeInt(headerLength + sceneLength + bodyLength);
	}

	private void writeBody(LittleEndianDataOutputStream dataOutputStream, byte[] body) throws IOException {
		int rest = body.length % 4 == 0 ? 0 : 4 - (body.length % 4);
		dataOutputStream.writeInt(body.length + rest); // Align to 4-bytes
		dataOutputStream.writeInt(BINARY_CHUNK);
		dataOutputStream.write(body);
		if (rest > 0) {
			dataOutputStream.write(pad(rest, (char) 0));
		}
	}

	private byte[] pad(int length, char c) {
		byte[] result = new byte[length];
		for (int i = 0; i < length; i++) {
			result[i] = (byte) c;
		}
		return result;
	}

	private void writeScene(LittleEndianDataOutputStream dataOutputStream, byte[] scene) throws IOException {
		int rest = scene.length % 4 == 0 ? 0 : 4 - (scene.length % 4);
		dataOutputStream.writeInt(scene.length + rest); // Align to 4-bytes
		dataOutputStream.writeInt(JSON_CHUNK);
		dataOutputStream.write(scene);
		if (rest > 0) {
			dataOutputStream.write(pad(rest, ' '));
		}
	}

	private void getProduct(IfcObjectDefinition product, ArrayNode parent, HashMap<String, ArrayNode> typedProduct,
			boolean parentSelected) {

		if (product instanceof IfcSpatialStructureElement) {
			EList<IfcProduct> ifcProducts = getChildProduct((IfcSpatialStructureElement) product);
			if (ifcProducts != null) {
				for (IfcProduct ifcProduct : ifcProducts) {
					String type = ifcProduct.eClass().getName() + " Group";
					if (!typedProduct.containsKey(type)) {
						ArrayNode productGroup = addGroup(type, parent);
						typedProduct.put(type, productGroup);
						LOGGER1.info("type:" + type);
					}
					try {
						if (!parentSelected && controlMode == ControlMode.PART && !partIDsMap.containsKey(type))
							continue;
						addGeometry(ifcProduct, typedProduct.get(type));
					} catch (SerializerException e) {
						// TODO Auto-generated catch block
						e.printStackTrace();
					}
				}
			}
		}
	}

	int autoUnknown = 1;

	private void buildGeometry() throws SerializerException {
		// need to reset
		autoUnknown = 1;
		// translationChildrenNode.removeAll();
		List<IfcSite> sites = model.getAllWithSubTypes(org.bimserver.models.ifc2x3tc1.IfcSite.class);
		boolean isParentSelected = false;
		for (IfcSite site : sites) {
			String name = site.getName();
			if (name == null)
				name = "Unknown" + autoUnknown++;
			if (controlMode == ControlMode.PART && !partIDsMap.containsKey(name))
				continue;
			isParentSelected = selectedPart.equals(name);
			LOGGER1.info("sites.getName():" + name);
			ArrayNode siteChild = addGroup(name, translationChildrenNode);
			addGeometry((IfcProduct) site, siteChild);
			// get site geometry

			HashMap<String, ArrayNode> typedProductSite = new HashMap<>();
			getProduct(site, siteChild, typedProductSite, isParentSelected);
			// traverseSpatialStructure(site);
			EList<IfcObjectDefinition> buildings = getChild(site);
			if (buildings != null) {
				for (IfcObjectDefinition building : buildings) {
					name = building.getName();
					if (name == null)
						name = "Unknown" + autoUnknown++;

					LOGGER1.info("__building.getName():" + name);
					if (!isParentSelected && controlMode == ControlMode.PART && !partIDsMap.containsKey(name))
						continue;

					isParentSelected |= selectedPart.equals(name);
					;
					LOGGER1.info("building.getName():" + name);
					ArrayNode buildingChild = addGroup(name, siteChild);
					EList<IfcObjectDefinition> buildingStoreys = getChild((IfcSpatialStructureElement) building);
					if (buildingStoreys != null)
						for (IfcObjectDefinition buildingStorey : buildingStoreys) {
							name = buildingStorey.getName();
							if (name == null)
								name = "Unknown" + autoUnknown++;

							if (!isParentSelected && controlMode == ControlMode.PART && !partIDsMap.containsKey(name))
								continue;

							isParentSelected |= selectedPart.equals(name);
							LOGGER1.info("buildingStorey.getName():" + name);
							ArrayNode buildingStoryChild = addGroup(name, buildingChild);
							HashMap<String, ArrayNode> typedProduct = new HashMap<>();
							// this. recursive
							getChildRecursive(buildingStorey, buildingStoryChild, typedProduct, isParentSelected);

						}
				}
			}
		}
	}

//	EList<IfcObjectDefinition> getChild(IfcObjectDefinition building) {
//		EList<IfcObjectDefinition> allChild = new BasicEList<IfcObjectDefinition>();
//		if (building instanceof IfcSpatialStructureElement) {
//			EList<IfcRelDecomposes> listbuilding = ((IfcSpatialStructureElement) building).getIsDecomposedBy();
//			// LOGGER.info("getIsDecomposedBy().size():"+listbuilding.size());
//			for (IfcRelDecomposes buildingstoryC : listbuilding) {
//				// ArrayNode buildingChild = addGroup(building.getName());
//				EList<IfcObjectDefinition> buildingstoryList = buildingstoryC.getRelatedObjects();
//				allChild.addAll(buildingstoryList);
//			}
//		}
//
//		if (building instanceof IfcBuildingElement) {
//			EList<IfcRelDecomposes> listbuilding = ((IfcBuildingElement) building).getIsDecomposedBy();
//			// LOGGER.info("getIsDecomposedBy().size():"+listbuilding.size());
//			for (IfcRelDecomposes buildingstoryC : listbuilding) {
//				// ArrayNode buildingChild = addGroup(building.getName());
//				EList<IfcObjectDefinition> buildingstoryList = buildingstoryC.getRelatedObjects();
//				allChild.addAll(buildingstoryList);
//			}
//		}
//
//		return allChild;
//	}
	EList<IfcObjectDefinition> getChild(IfcSpatialStructureElement group) {
		EList<IfcObjectDefinition> allChild = new BasicEList<IfcObjectDefinition>();
		EList<IfcRelDecomposes> listbuilding = group.getIsDecomposedBy();
		// LOGGER.info("getIsDecomposedBy().size():"+listbuilding.size());
		for (IfcRelDecomposes buildingstoryC : listbuilding) {
			// ArrayNode buildingChild = addGroup(building.getName());
			EList<IfcObjectDefinition> buildingstoryList = buildingstoryC.getRelatedObjects();
			allChild.addAll(buildingstoryList);
		}
//		if (group instanceof IfcBuildingElement) {
//			EList<IfcRelDecomposes> listElement = ((IfcBuildingElement) group).getIsDecomposedBy();
//			for (IfcRelDecomposes element : listElement) {
//				// ArrayNode buildingChild = addGroup(building.getName());
//				EList<IfcObjectDefinition> childElements = element.getRelatedObjects();
//				allChild.addAll((Collection<? extends IfcProduct>) childElements);
//			}
//		}

		return allChild;
	}

//	EList<IfcProduct> getChildProduct(IfcObjectDefinition product2) {
//		EList<IfcProduct> productAll = new BasicEList<IfcProduct>();
//		if (product2 instanceof IfcSpatialStructureElement) {
//			EList<IfcRelContainedInSpatialStructure> productsC = ((IfcSpatialStructureElement) product2)
//					.getContainsElements();
//			// /LOGGER.info("productC.size():"+productsC.size());
//			for (IfcRelContainedInSpatialStructure product : productsC) {
//				EList<IfcProduct> products = product.getRelatedElements();
//				for (IfcProduct element : products) {
//					if (element instanceof IfcBuildingElement) {
//						EList<IfcRelDecomposes> listElement = ((IfcBuildingElement) element).getIsDecomposedBy();
//						for (IfcRelDecomposes elementA : listElement) {
//							// ArrayNode buildingChild = addGroup(building.getName());
//							EList<IfcObjectDefinition> childElements = elementA.getRelatedObjects();
//							productAll.addAll((Collection<? extends IfcProduct>) childElements);
//						}
////						productAll.addAll((Collection<? extends IfcProduct>) listElement);
//					}
//				}
//				productAll.addAll(products);
//			}
//		}
//
//		return productAll;
//	}
	EList<IfcProduct> getChildElement(IfcProduct group) {
		EList<IfcProduct> productAll = new BasicEList<IfcProduct>();
		if (group instanceof IfcBuildingElement) {
			EList<IfcRelDecomposes> listElement = ((IfcBuildingElement) group).getIsDecomposedBy();
			for (IfcRelDecomposes element : listElement) {
				// ArrayNode buildingChild = addGroup(building.getName());
				EList<IfcObjectDefinition> childElements = element.getRelatedObjects();
				productAll.addAll((Collection<? extends IfcProduct>) childElements);
			}
		}

		return productAll;
	}

	EList<IfcProduct> getChildProduct(IfcSpatialStructureElement group) {

		EList<IfcRelContainedInSpatialStructure> productsC = group.getContainsElements();
		// /LOGGER.info("productC.size():"+productsC.size());
		EList<IfcProduct> productAll = new BasicEList<IfcProduct>();
		for (IfcRelContainedInSpatialStructure product : productsC) {
			EList<IfcProduct> products = product.getRelatedElements();
			productAll.addAll(products);
		}

		return productAll;
	}

	List<IfcProduct> listProduct = new ArrayList<IfcProduct>();

	void doSomethingWith(IfcSpatialStructureElement spatialStructure) {
		for (IfcRelContainedInSpatialStructure containment : spatialStructure.getContainsElements()) {
			for (IfcProduct product : containment.getRelatedElements()) {
				// do something with your product, e.g. fire detector
				// LOGGER.info("Child "+product.getName());
				listProduct.add(product);
			}
		}
	}

	void traverseSpatialStructure(IfcSpatialStructureElement parent) {
		for (IfcRelDecomposes aggregation : parent.getIsDecomposedBy()) {
			for (IfcObjectDefinition child : aggregation.getRelatedObjects()) {
				if (child instanceof IfcSpatialStructureElement) {
					doSomethingWith((IfcSpatialStructureElement) child);
					traverseSpatialStructure((IfcSpatialStructureElement) child);
				}
			}
		}
	}

	private void getChildRecursive(IfcObjectDefinition product, ArrayNode parent,
			HashMap<String, ArrayNode> typedProduct, boolean parentSelected) {
		if (product == null)
			return;

		getProduct(product, parent, typedProduct, parentSelected);
		if (product instanceof IfcSpatialStructureElement) {
			EList<IfcProduct> ifcProducts = getChildProduct((IfcSpatialStructureElement) product);
			if (ifcProducts != null && ifcProducts.size() > 0) {
				for (IfcProduct ifcProduct : ifcProducts) {
					String parentType = ifcProduct.eClass().getName() + " Group";
					String type = ifcProduct.getName() + " Group";
					LOGGER.info("type --------------------------: " + type);
					if (!typedProduct.containsKey(type)) {
						ArrayNode productGroup = addGroup(type, typedProduct.get(parentType));
//						typedProduct.put(type, productGroup);
						if (ifcProduct instanceof IfcSpatialStructureElement) {
							EList<IfcObjectDefinition> childs = getChild((IfcSpatialStructureElement) ifcProduct);
							if (childs != null && childs.size() > 0) {
								for (IfcObjectDefinition child : childs) {
									// ArrayNode subGroup = addGroup(child.getName(), parent);
									String type1 = child.getName();
									if (!typedProduct.containsKey(type1)) {
										ArrayNode productGroup1 = addGroup(type1, productGroup);
										typedProduct.put(type1, productGroup1);
										// LOGGER.info("type:"+type);
										try {
											// LOGGER.info("Hidden name:"+((IfcProduct)child).getName());
											if (parentSelected || controlMode != ControlMode.PART
													|| partIDsMap.containsKey(type1))
												addGeometry((IfcProduct) child, typedProduct.get(type1));
										} catch (SerializerException e) {
											// TODO Auto-generated catch block
											LOGGER1.info("getChildRecursive " + e.getMessage());
										}
									}
								}
							}
						} else {
							if (ifcProduct instanceof IfcProduct) {
								EList<IfcProduct> elementChilds = getChildElement((IfcProduct) ifcProduct);
								if (elementChilds != null && elementChilds.size() > 0) {
									for (IfcObjectDefinition element : elementChilds) {
										// ArrayNode subGroup = addGroup(child.getName(), parent);
										String type2 = element.getName();
										if (!typedProduct.containsKey(type2)) {
											ArrayNode productGroup2 = addGroup(type2, productGroup);
											typedProduct.put(type2, productGroup2);
											// LOGGER.info("type:"+type);
											try {
												// LOGGER.info("Hidden name:"+((IfcProduct)child).getName());
												if (parentSelected || controlMode != ControlMode.PART
														|| partIDsMap.containsKey(type2))
													addGeometry((IfcProduct) element, typedProduct.get(type2));
											} catch (SerializerException e) {
												// TODO Auto-generated catch block
												LOGGER1.info("getChildRecursive " + e.getMessage());
											}
										}
									}
								}
							}
						}
						getChildRecursive(ifcProduct, productGroup, typedProduct, parentSelected);
					}
				}
			}

		}

	}

	ArrayNode addGroup(String name, ArrayNode parent) {
		if (state == 0)
			return null;
		ObjectNode parentNode = OBJECT_MAPPER.createObjectNode();
		ArrayNode childs = OBJECT_MAPPER.createArrayNode();
		// childs.add(nodeId);
		parentNode.set("children", childs);
		parentNode.put("name", name == null ? "Unknown" : name);
		// if(parent != null){
		// parent.add(parentNode);
		// }else
		nodes.add(parentNode);
		parent.add(nodes.size() - 1);
		return childs;
	}

	private void addGeometry(IfcProduct ifcProduct, ArrayNode parentNode) throws SerializerException {
		if (state == 0) {
			listProduct.add(ifcProduct);
			return;
		}
		if (!checkGeometry(ifcProduct, true))
			return;
		GeometryInfo geometryInfo = ifcProduct.getGeometry();

		// if (!ifcProduct.eClass().getName().equals("IfcOpeningElement")
		// && geometryInfo != null
		// && geometryInfo.getData().getVertices().length > 0)
		{
			int startPositionIndices = newIndicesBuffer.position();
			int startPositionVertices = newVerticesBuffer.position();
			int startPositionNormals = newNormalsBuffer.position();
			int startPositionColors = newColorsBuffer.position();

			GeometryData data = geometryInfo.getData();

			ByteBuffer indicesBuffer = ByteBuffer.wrap(data.getIndices().getData());
			indicesBuffer.order(ByteOrder.LITTLE_ENDIAN);
			IntBuffer indicesIntBuffer = indicesBuffer.asIntBuffer();

			ByteBuffer verticesBuffer = ByteBuffer.wrap(data.getVertices().getData());
			verticesBuffer.order(ByteOrder.LITTLE_ENDIAN);
			// FloatBuffer verticesFloatBuffer = verticesBuffer.asFloatBuffer();

			ByteBuffer normalsBuffer = ByteBuffer.wrap(data.getNormals().getData());
			normalsBuffer.order(ByteOrder.LITTLE_ENDIAN);
			// FloatBuffer normalsFloatBuffer = normalsBuffer.asFloatBuffer();

			int maxVal = 0;
			for (int i = 0; i < indicesIntBuffer.capacity(); i++) {
				int index = indicesIntBuffer.get(i);
				newIndicesBuffer.putInt(index);
				if (index > maxVal) {
					maxVal = index;
				}
			}

			int[] min = new int[] { 0 };
			int[] max = new int[] { maxVal };

			for (int i = 0; i < data.getNrVertices(); i++) {
				newVerticesBuffer.putFloat((float) verticesBuffer.getDouble());
			}
			newNormalsBuffer.put(data.getNormals().getData());
			if (data.getColorsQuantized() != null) {
				newColorsBuffer.put(data.getColorsQuantized().getData());
			} else {
				Vector4f color = data.getColor();
				if (color == null) {
					color = data.getMostUsedColor();
				}
				if (color != null) {
					for (int i = 0; i < data.getNrVertices() / 3; i++) {
						newColorsBuffer.put(UnsignedBytes.checkedCast((int) (color.getX() * 255)));
						newColorsBuffer.put(UnsignedBytes.checkedCast((int) (color.getY() * 255)));
						newColorsBuffer.put(UnsignedBytes.checkedCast((int) (color.getZ() * 255)));
						newColorsBuffer.put(UnsignedBytes.checkedCast((int) (color.getW() * 255)));
					}
				} else {
					for (int i = 0; i < data.getNrVertices() / 3; i++) {
						newColorsBuffer.put(UnsignedBytes.checkedCast(50));
						newColorsBuffer.put(UnsignedBytes.checkedCast(50));
						newColorsBuffer.put(UnsignedBytes.checkedCast(50));
						newColorsBuffer.put(UnsignedBytes.checkedCast(255));
					}
				}
			}

			int totalNrIndices = indicesIntBuffer.capacity();

			ArrayNode primitivesNode = OBJECT_MAPPER.createArrayNode();

			ObjectNode primitiveNode = OBJECT_MAPPER.createObjectNode();

			int indicesAccessor = addIndicesAccessor(ifcProduct, indicesBufferView, startPositionIndices,
					totalNrIndices, min, max);
			int verticesAccessor = addVerticesAccessor(ifcProduct, verticesBufferView, startPositionVertices,
					data.getNrVertices() / 3);
			int normalsAccessor = addNormalsAccessor(ifcProduct, normalsBufferView, startPositionNormals,
					data.getNrNormals() / 3);
			int colorAccessor = -1;
			if (colorsBufferView == -1) {
				colorsBufferView = createBufferView(totalColorsByteLength,
						totalIndicesByteLength + totalVerticesByteLength + totalNormalsByteLength, ARRAY_BUFFER, 4);
			}
			colorAccessor = addColorsAccessor(ifcProduct, colorsBufferView, startPositionColors,
					data.getNrVertices() / 3);
			primitivesNode.add(primitiveNode);

			primitiveNode.put("indices", indicesAccessor);
			primitiveNode.put("mode", TRIANGLES);
			ObjectNode attributesNode = OBJECT_MAPPER.createObjectNode();
			primitiveNode.set("attributes", attributesNode);
			attributesNode.put("NORMAL", normalsAccessor);
			attributesNode.put("POSITION", verticesAccessor);
			if (colorAccessor != -1) {
				attributesNode.put("COLOR_0", colorAccessor);
				primitiveNode.put("material", vertexColorIndex);
			} else {
				primitiveNode.put("material", createOrGetMaterial(ifcProduct.eClass().getName(),
						IfcColors.getDefaultColor(ifcProduct.eClass().getName())));
			}

			int meshId = addMesh(ifcProduct, primitivesNode);
			int nodeId = addNode(meshId, ifcProduct);
			parentNode.add(nodeId);
			// LOGGER.info("geometry name :"+ifcProduct.getName());
		}
		/*
		 * else { int maxVal = 0; for (int i = 0; i < indicesIntBuffer.capacity(); i++)
		 * { int index = indicesIntBuffer.get(i); if (index > Short.MAX_VALUE) { throw
		 * new SerializerException( "Index too large to store as short " + index); }
		 * newIndicesBuffer.putShort((short) (index)); if (index > maxVal) { maxVal =
		 * index; } }
		 * 
		 * int[] min = new int[] { 0 }; int[] max = new int[] { maxVal };
		 * 
		 * newVerticesBuffer.put(data.getVertices());
		 * newNormalsBuffer.put(data.getNormals()); if (data.getMaterials() != null) {
		 * newColorsBuffer.put(data.getMaterials()); }
		 * 
		 * int totalNrIndices = indicesIntBuffer.capacity();
		 * 
		 * ArrayNode primitivesNode = OBJECT_MAPPER.createArrayNode();
		 * 
		 * ObjectNode primitiveNode = OBJECT_MAPPER.createObjectNode();
		 * 
		 * int indicesAccessor = addIndicesAccessor(ifcProduct, indicesBufferView,
		 * startPositionIndices, totalNrIndices, min, max); int verticesAccessor =
		 * addVerticesAccessor(ifcProduct, verticesBufferView, startPositionVertices,
		 * data.getVertices().length / 3); int normalsAccessor =
		 * addNormalsAccessor(ifcProduct, normalsBufferView, startPositionNormals,
		 * data.getNormals().length / 3); int colorAccessor = -1; if
		 * (data.getMaterials() != null) { if (colorsBufferView == -1) {
		 * colorsBufferView = createBufferView( totalColorsByteLength,
		 * totalIndicesByteLength + totalVerticesByteLength + totalNormalsByteLength,
		 * ARRAY_BUFFER, 4); } colorAccessor = addColorsAccessor(ifcProduct,
		 * colorsBufferView, startPositionColors, data.getVertices().length / 12); }
		 * primitivesNode.add(primitiveNode);
		 * 
		 * primitiveNode.put("indices", indicesAccessor); primitiveNode.put("mode",
		 * TRIANGLES); ObjectNode attributesNode = OBJECT_MAPPER.createObjectNode();
		 * primitiveNode.set("attributes", attributesNode); attributesNode.put("NORMAL",
		 * normalsAccessor); attributesNode.put("POSITION", verticesAccessor); if
		 * (colorAccessor != -1) { attributesNode.put("COLOR_0", colorAccessor);
		 * primitiveNode.put("material", vertexColorIndex); } else { primitiveNode.put(
		 * "material", createOrGetMaterial(ifcProduct.eClass().getName(),
		 * IfcColors.getDefaultColor(ifcProduct .eClass().getName()))); }
		 * 
		 * int meshId = addMesh(ifcProduct, primitivesNode); int nodeId =
		 * addNode(meshId, ifcProduct);
		 * 
		 * parentNode.add(nodeId);
		 * 
		 * // LOGGER.info("geometry(IfcOpeningElement) name :"+ifcProduct.getName()); }
		 * }
		 */

	}

	final Logger LOGGER = LoggerFactory.getLogger(BinaryGltfSerializer2.class);
	private int state = 0;
	HashMap<String, Integer> typedProductFromModel = new HashMap();
	HashMap<String, Integer> typedProductDebug = new HashMap();
	int indicesBufferView;
	int verticesBufferView;
	int normalsBufferView;
	int colorsBufferView = -1;
	int maxIndexValues = 16389;
	int totalColorsByteLength = 0;
	int totalBodyByteLength = 0;
	int totalIndicesByteLength = 0;
	int totalVerticesByteLength = 0;
	int totalNormalsByteLength = 0;
	ByteBuffer newIndicesBuffer, newVerticesBuffer, newNormalsBuffer, newColorsBuffer;

	private int calculateBuffer(IfcProduct ifcProduct) {
		int size = 0;
		// if (!ifcProduct.eClass().getName().equals("IfcOpeningElement")
		// && geometryInfo != null
		// && geometryInfo.getData().getVertices().length > 0)
		if (!checkGeometry(ifcProduct, false))
			return 0;
		GeometryInfo geometryInfo = ifcProduct.getGeometry();
		GeometryData data = geometryInfo.getData();
		totalIndicesByteLength += data.getNrIndices() * 4;
		totalVerticesByteLength += data.getNrVertices() * 4;
		totalNormalsByteLength += data.getNrNormals() * 4;
		if (data.getColorsQuantized() != null) {
			totalColorsByteLength += data.getColorsQuantized().getData().length;
		} else {
			totalColorsByteLength += (data.getNrVertices() / 3) * 4;
		}
		return size;
	}

	private void updateMatrix(IfcProduct ifcProduct) {
		GeometryInfo geometryInfo = ifcProduct.getGeometry();
		// if (!ifcProduct.eClass().getName().equals("IfcOpeningElement")
		// && geometryInfo != null)
		if (checkGeometry(ifcProduct, false)) {
			ByteBuffer matrixByteBuffer = ByteBuffer.wrap(ifcProduct.getGeometry().getTransformation());
			matrixByteBuffer.order(ByteOrder.LITTLE_ENDIAN);
			DoubleBuffer doubleBuffer = matrixByteBuffer.asDoubleBuffer();
			double[] matrix = new double[16];
			for (int i = 0; i < doubleBuffer.capacity(); i++) {
				matrix[i] = (float) doubleBuffer.get();
			}

			updateExtends(geometryInfo, matrix);
		}
	}
}