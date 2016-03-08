package org.bimserver.gltf;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.DoubleBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;

import org.bimserver.models.geometry.GeometryData;
import org.bimserver.models.geometry.GeometryInfo;
import org.bimserver.models.ifc2x3tc1.IfcProduct;
import org.bimserver.plugins.serializers.EmfSerializer;
import org.bimserver.plugins.serializers.ProgressReporter;
import org.bimserver.plugins.serializers.SerializerException;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.common.base.Charsets;
import com.google.common.io.LittleEndianDataOutputStream;

/**
 * @author Ruben de Laat
 *
 *         Annoying things about glTF so far: - Total and scene length have to
 *         be computed in advance, so no streaming is possible
 *
 */
public class BinaryGltfSerializer extends EmfSerializer {

	private static final int FLOAT_VEC_4 = 35666;
	private static final int SHORT = 5122;
	private static final int ARRAY_BUFFER = 34962;
	private static final int ELEMENT_ARRAY_BUFFER = 34963;
	private static final String MAGIC = "glTF";
	private static final int UNSIGNED_SHORT = 5123;
	private static final int TRIANGLES = 4;
	private static final int FLOAT = 5126;
	private int JSON_SCENE_FORMAT = 0;
	private int FORMAT_VERSION_1 = 1;

	private final ObjectMapper objectMapper = new ObjectMapper();
	private ObjectNode buffers;
	private ByteBuffer body;
	private ObjectNode meshes;
	private ObjectNode accessors;
	private int accessorCounter = 0;
	private int bufferViewCounter;
	private ObjectNode buffersViews;
	private ArrayNode defaultSceneNodes;
	private ObjectNode scenesNode;
	private ObjectNode gltfNode;
	private ObjectNode nodes;
	private byte[] fragmentShaderBytes;
	private byte[] vertexShaderBytes;
	float[] min = {Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE};
	float[] max = {-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};
	private ArrayNode childrenNode;
	private ArrayNode modelTranslation;

	public BinaryGltfSerializer(byte[] fragmentShaderBytes, byte[] vertexShaderBytes) {
		this.fragmentShaderBytes = fragmentShaderBytes;
		this.vertexShaderBytes = vertexShaderBytes;
	}

	@Override
	protected boolean write(OutputStream outputStream, ProgressReporter progressReporter) throws SerializerException {
		gltfNode = objectMapper.createObjectNode();

		buffers = objectMapper.createObjectNode();
		meshes = objectMapper.createObjectNode();
		buffersViews = objectMapper.createObjectNode();
		scenesNode = objectMapper.createObjectNode();
		accessors = objectMapper.createObjectNode();
		nodes = objectMapper.createObjectNode();

		gltfNode.set("meshes", meshes);
		gltfNode.set("bufferViews", buffersViews);
		gltfNode.set("scenes", scenesNode);
		gltfNode.set("accessors", accessors);
		gltfNode.set("nodes", nodes);
		gltfNode.set("buffers", buffers);

		try {
			LittleEndianDataOutputStream dataOutputStream = new LittleEndianDataOutputStream(outputStream);

			generateSceneAndBody();

//			StringWriter stringWriter = new StringWriter();
//			objectMapper.writerWithDefaultPrettyPrinter().writeValue(stringWriter, gltfNode);
//			System.out.println(stringWriter);

			byte[] sceneBytes = gltfNode.toString().getBytes(Charsets.UTF_8);
			writeHeader(dataOutputStream, 20, sceneBytes.length, body.capacity());
			writeScene(dataOutputStream, sceneBytes);
			writeBody(dataOutputStream, body.array());
		} catch (IOException e) {
			throw new SerializerException(e);
		}
		return false;
	}

	private ObjectNode createModelNode() {
		ObjectNode modelNode = objectMapper.createObjectNode();

		childrenNode = objectMapper.createArrayNode();
		modelNode.set("children", childrenNode);
		modelTranslation = objectMapper.createArrayNode();
		modelNode.set("translation", modelTranslation);
		
		ArrayNode rotation = objectMapper.createArrayNode();
		modelNode.set("rotation", rotation);
		rotation.add(1f);
		rotation.add(0f);
		rotation.add(0f);
		rotation.add(-1f);
		
		nodes.set("modelNode", modelNode);
		defaultSceneNodes.add("modelNode");
		
		return modelNode;
	}

	private void generateSceneAndBody() {
		int totalBodyByteLength = 0;
		int totalIndicesByteLength = 0;
		int totalVerticesByteLength = 0;
		int totalNormalsByteLength = 0;
		int totalColorsByteLength = 0;

		for (IfcProduct ifcProduct : model.getAllWithSubTypes(IfcProduct.class)) {
			GeometryInfo geometryInfo = ifcProduct.getGeometry();
			if (geometryInfo != null) {
				GeometryData data = geometryInfo.getData();
				int nrIndicesBytes = data.getIndices().length / 2;
				int byteLength = nrIndicesBytes + data.getVertices().length + data.getNormals().length;

				if (data.getMaterials() != null) {
					totalColorsByteLength += data.getMaterials().length;
					byteLength += data.getMaterials().length;
				}
				
				totalIndicesByteLength += nrIndicesBytes;
				totalVerticesByteLength += data.getVertices().length;				
				totalNormalsByteLength += data.getNormals().length;				
				totalBodyByteLength += byteLength;
			}
		}

		body = ByteBuffer.allocate(totalBodyByteLength + vertexShaderBytes.length + fragmentShaderBytes.length);
		body.order(ByteOrder.LITTLE_ENDIAN);
		
		ByteBuffer newIndicesBuffer = ByteBuffer.allocate(totalIndicesByteLength);
		newIndicesBuffer.order(ByteOrder.LITTLE_ENDIAN);
		
		ByteBuffer newVerticesBuffer = ByteBuffer.allocate(totalVerticesByteLength);
		newVerticesBuffer.order(ByteOrder.LITTLE_ENDIAN);
		
		ByteBuffer newNormalsBuffer = ByteBuffer.allocate(totalNormalsByteLength);
		ByteBuffer newColorsBuffer = ByteBuffer.allocate(totalColorsByteLength);

		String indicesBufferView = createBufferView(totalIndicesByteLength, 0, ELEMENT_ARRAY_BUFFER);
		String verticesBufferView = createBufferView(totalVerticesByteLength, totalIndicesByteLength, ARRAY_BUFFER);
		String normalsBufferView = createBufferView(totalNormalsByteLength, totalIndicesByteLength + totalVerticesByteLength, ARRAY_BUFFER);
		String colorsBufferView = null;
		
		scenesNode.set("defaultScene", createDefaultScene());
		createModelNode();

		for (IfcProduct ifcProduct : model.getAllWithSubTypes(IfcProduct.class)) {
			GeometryInfo geometryInfo = ifcProduct.getGeometry();
			if (geometryInfo != null) {
				updateExtends(geometryInfo);
			}
		}
		
		float[] offsets = getOffsets();
		
		modelTranslation.add(-offsets[0]);
		modelTranslation.add(-offsets[1]);
		modelTranslation.add(-offsets[2]);
		
		for (IfcProduct ifcProduct : model.getAllWithSubTypes(IfcProduct.class)) {
			GeometryInfo geometryInfo = ifcProduct.getGeometry();
			if (geometryInfo != null && geometryInfo.getData().getVertices().length > 0) {
				int startPositionIndices = newIndicesBuffer.position();
				int startPositionVertices = newVerticesBuffer.position();
				int startPositionNormals = newNormalsBuffer.position();
				int startPositionColors = newColorsBuffer.position();
				GeometryData data = geometryInfo.getData();
				ByteBuffer byteBuffer = ByteBuffer.wrap(data.getIndices());
				byteBuffer.order(ByteOrder.LITTLE_ENDIAN);
				IntBuffer intBuffer = byteBuffer.asIntBuffer();
				for (int i=0; i<intBuffer.capacity(); i++) {
					int index = intBuffer.get(i);
					newIndicesBuffer.putShort((short)(index));
				}

				newVerticesBuffer.put(data.getVertices());
				newNormalsBuffer.put(data.getNormals());
				if (data.getMaterials() != null) {
					newColorsBuffer.put(data.getMaterials());
				}
				
				String indicesAccessor = addIndicesAccessor(ifcProduct, indicesBufferView, startPositionIndices);
				String verticesAccessor = addVerticesAccessor(ifcProduct, verticesBufferView, startPositionVertices);
				String normalsAccessor = addNormalsAccessor(ifcProduct, normalsBufferView, startPositionNormals);
				String colorAccessor = null;
				if (data.getMaterials() != null) {
					if (colorsBufferView == null) {
						colorsBufferView = createBufferView(totalColorsByteLength, totalIndicesByteLength + totalVerticesByteLength + totalNormalsByteLength, ARRAY_BUFFER);
					}
					colorAccessor = addColorsAccessor(ifcProduct, colorsBufferView, startPositionColors);
				}
				String meshName = addMesh(ifcProduct, indicesAccessor, normalsAccessor, verticesAccessor, colorAccessor);

				String nodeName = addNode(meshName, ifcProduct);
				childrenNode.add(nodeName);
			}
		}
		
		newIndicesBuffer.position(0);
		newVerticesBuffer.position(0);
		newNormalsBuffer.position(0);
		newColorsBuffer.position(0);
		
		body.put(newIndicesBuffer);
		body.put(newVerticesBuffer);
		body.put(newNormalsBuffer);
		body.put(newColorsBuffer);

		String fragmentShaderBufferViewName = createBufferView(fragmentShaderBytes.length, body.position());
		body.put(fragmentShaderBytes);

		String vertexShaderBufferViewName = createBufferView(vertexShaderBytes.length, body.position());
		body.put(vertexShaderBytes);
		
		// rootNode.set("buffers", createBuffers());
		// rootNode.set("accessors", createAccessors());
		gltfNode.set("animations", createAnimations());
		gltfNode.set("asset", createAsset());
		// rootNode.set("bufferViews", createBufferViews());
		gltfNode.set("materials", createMaterials());
		// rootNode.set("meshes", createMeshes());
		gltfNode.set("programs", createPrograms());
		gltfNode.put("scene", "defaultScene");
		gltfNode.set("shaders", createShaders(fragmentShaderBufferViewName, vertexShaderBufferViewName));
		gltfNode.set("skins", createSkins());
		gltfNode.set("techniques", createTechniques());

		addBuffer("binary_glTF", "arraybuffer", body.capacity());

		ArrayNode extensions = objectMapper.createArrayNode();
		extensions.add("KHR_binary_glTF");
		gltfNode.set("extensionsUsed", extensions);
	}

	private float[] getOffsets() {
		float[] changes = new float[3];
		for (int i=0; i<3; i++) {
			changes[i] = (max[i] - min[i]) / 2.0f + min[i];
		}
		return changes;
	}
	
	private void updateExtends(GeometryInfo geometryInfo) {
		ByteBuffer verticesByteBufferBuffer = ByteBuffer.wrap(geometryInfo.getData().getVertices());
		verticesByteBufferBuffer.order(ByteOrder.LITTLE_ENDIAN);
		FloatBuffer floatBuffer = verticesByteBufferBuffer.asFloatBuffer();
		for (int i=0; i<floatBuffer.capacity(); i+=3) {
			for (int j=0; j<3; j++) {
				float value = floatBuffer.get(i + j);
				if (value > max[j]) {
					max[j] = value;
				}
				if (value < min[j]) {
					min[j] = value;
				}
			}
		}
	}

	private String addNode(String meshName, IfcProduct ifcProduct) {
		String nodeName = "node_" + ifcProduct.getOid();
		ObjectNode nodeNode = objectMapper.createObjectNode();

		ArrayNode matrixArray = objectMapper.createArrayNode();
		ByteBuffer matrixByteBuffer = ByteBuffer.wrap(ifcProduct.getGeometry().getTransformation());
		matrixByteBuffer.order(ByteOrder.LITTLE_ENDIAN);
		DoubleBuffer doubleBuffer = matrixByteBuffer.asDoubleBuffer();
		for (int i = 0; i < 16; i++) {
			matrixArray.add(doubleBuffer.get(i));
		}

		ArrayNode meshes = objectMapper.createArrayNode();
		meshes.add(meshName);

		nodeNode.set("meshes", meshes);
		nodeNode.set("matrix", matrixArray);
		nodes.set(nodeName, nodeNode);

		return nodeName;
	}

	private ObjectNode createDefaultScene() {
		ObjectNode sceneNode = objectMapper.createObjectNode();

		defaultSceneNodes = objectMapper.createArrayNode();

		sceneNode.set("nodes", defaultSceneNodes);

		return sceneNode;
	}

	private String createBufferView(int byteLength, int byteOffset) {
		return createBufferView(byteLength, byteOffset, -1);
	}
	
	private String createBufferView(int byteLength, int byteOffset, int target) {
		String name = "bufferView_" + (bufferViewCounter++);
		ObjectNode bufferViewNode = objectMapper.createObjectNode();

		bufferViewNode.put("buffer", "binary_glTF");
		bufferViewNode.put("byteLength", byteLength);
		bufferViewNode.put("byteOffset", byteOffset);
		if (target != -1) {
			bufferViewNode.put("target", target);
		}

		buffersViews.set(name, bufferViewNode);

		return name;
	}

	private String addNormalsAccessor(IfcProduct ifcProduct, String bufferViewName, int byteOffset) {
		String accessorName = "accessor_normal_" + (accessorCounter++);

		ObjectNode accessor = objectMapper.createObjectNode();
		accessor.put("bufferView", bufferViewName);
		accessor.put("byteOffset", byteOffset);
		accessor.put("byteStride", 12);
		accessor.put("componentType", FLOAT);
		accessor.put("count", ifcProduct.getGeometry().getData().getNormals().length / 4);
		accessor.put("type", "VEC3");

		ArrayNode min = objectMapper.createArrayNode();
		min.add(-1d);
		min.add(-1d);
		min.add(-1d);
		ArrayNode max = objectMapper.createArrayNode();
		max.add(1);
		max.add(1);
		max.add(1);

		accessor.set("min", min);
		accessor.set("max", max);

		accessors.set(accessorName, accessor);

		return accessorName;
	}

	private String addColorsAccessor(IfcProduct ifcProduct, String bufferViewName, int byteOffset) {
		String accessorName = "accessor_color_" + (accessorCounter++);
		
		ObjectNode accessor = objectMapper.createObjectNode();
		accessor.put("bufferView", bufferViewName);
		accessor.put("byteOffset", byteOffset);
		accessor.put("byteStride", 16);
		accessor.put("componentType", FLOAT);
		accessor.put("count", ifcProduct.getGeometry().getData().getMaterials() == null ? 0 : ifcProduct.getGeometry().getData().getMaterials().length / 4);
		accessor.put("type", "VEC4");
		
//		ArrayNode min = objectMapper.createArrayNode();
//		min.add(-1d);
//		min.add(-1d);
//		min.add(-1d);
//		ArrayNode max = objectMapper.createArrayNode();
//		max.add(1);
//		max.add(1);
//		max.add(1);
//		
//		accessor.set("min", min);
//		accessor.set("max", max);
		
		accessors.set(accessorName, accessor);
		
		return accessorName;
	}

	private String addVerticesAccessor(IfcProduct ifcProduct, String bufferViewName, int startPosition) {
		String accessorName = "accessor_vertex_" + (accessorCounter++);

		GeometryData data = ifcProduct.getGeometry().getData();
		ByteBuffer verticesBuffer = ByteBuffer.wrap(data.getVertices());

		ObjectNode accessor = objectMapper.createObjectNode();
		accessor.put("bufferView", bufferViewName);
		accessor.put("byteOffset", startPosition);
		accessor.put("byteStride", 12);
		accessor.put("componentType", FLOAT);
		accessor.put("count", verticesBuffer.capacity() / 4);
		accessor.put("type", "VEC3");

		verticesBuffer.order(ByteOrder.LITTLE_ENDIAN);
		double[] min = { Double.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE };
		double[] max = { -Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE };
		for (int i = 0; i < verticesBuffer.capacity(); i += 3) {
			for (int j = 0; j < 3; j++) {
				double val = verticesBuffer.get(i + j);
				if (val > max[j]) {
					max[j] = val;
				}
				if (val < min[j]) {
					min[j] = val;
				}
			}
		}

		ArrayNode minNode = objectMapper.createArrayNode();
		minNode.add(min[0]);
		minNode.add(min[1]);
		minNode.add(min[2]);
		ArrayNode maxNode = objectMapper.createArrayNode();
		maxNode.add(max[0]);
		maxNode.add(max[1]);
		maxNode.add(max[2]);

		accessor.set("min", minNode);
		accessor.set("max", maxNode);

		accessors.set(accessorName, accessor);

		return accessorName;
	}

	private String addIndicesAccessor(IfcProduct ifcProduct, String bufferViewName, int offsetBytes) {
		String accessorName = "accessor_index_" + (accessorCounter++);

		ObjectNode accessor = objectMapper.createObjectNode();
		accessor.put("bufferView", bufferViewName);
		accessor.put("byteOffset", offsetBytes);
		accessor.put("byteStride", 0);
		accessor.put("componentType", UNSIGNED_SHORT);
		accessor.put("count", ifcProduct.getGeometry().getData().getIndices().length / 4);
		accessor.put("type", "SCALAR");

		accessors.set(accessorName, accessor);

		return accessorName;
	}

	private String addMesh(IfcProduct ifcProduct, String indicesAccessor, String normalAccessor, String vertexAccessor, String colorAccessor) {
		ObjectNode meshNode = objectMapper.createObjectNode();
		String meshName = "mesh_" + ifcProduct.getOid();
		meshNode.put("name", meshName);
		ArrayNode primitivesNode = objectMapper.createArrayNode();
		ObjectNode primitiveNode = objectMapper.createObjectNode();

		ObjectNode attributesNode = objectMapper.createObjectNode();
		primitiveNode.set("attributes", attributesNode);
		attributesNode.put("NORMAL", normalAccessor);
		attributesNode.put("POSITION", vertexAccessor);
		if (colorAccessor != null) {
			attributesNode.put("COLOR", colorAccessor);
		}
		primitiveNode.put("material", "defaultMaterial");

		primitiveNode.put("indices", indicesAccessor);
		primitiveNode.put("mode", TRIANGLES);

		primitivesNode.add(primitiveNode);
		meshNode.set("primitives", primitivesNode);
		meshes.set(meshName, meshNode);

		return meshName;
	}

	private void addBuffer(String name, String type, int byteLength) {
		ObjectNode bufferNode = objectMapper.createObjectNode();

		bufferNode.put("byteLength", byteLength);
		bufferNode.put("type", type);
		bufferNode.put("uri", "data:,");

		buffers.set(name, bufferNode);
	}

	private JsonNode createAnimations() {
		// TODO
		return objectMapper.createObjectNode();
	}

	private JsonNode createAccessors() {
		// TODO
		return objectMapper.createObjectNode();
	}

	private JsonNode createTechniques() {
		ObjectNode defaultTechnique = objectMapper.createObjectNode();

		ObjectNode techniques = objectMapper.createObjectNode();
		ObjectNode attributes = objectMapper.createObjectNode();
		ObjectNode parameters = objectMapper.createObjectNode();
		ObjectNode states = objectMapper.createObjectNode();
		ObjectNode uniforms = objectMapper.createObjectNode();

		attributes.put("a_normal", "normal");
		attributes.put("a_position", "position");
		attributes.put("a_color", "color");

//		ObjectNode diffuse = objectMapper.createObjectNode();
//		diffuse.put("type", 35666);
//		parameters.set("diffuse", diffuse);

		ObjectNode modelViewMatrix = objectMapper.createObjectNode();
		modelViewMatrix.put("semantic", "MODELVIEW");
		modelViewMatrix.put("type", 35676);
		parameters.set("modelViewMatrix", modelViewMatrix);

		ObjectNode normal = objectMapper.createObjectNode();
		normal.put("semantic", "NORMAL");
		normal.put("type", 35665);
		parameters.set("normal", normal);

		ObjectNode normalMatrix = objectMapper.createObjectNode();
		normalMatrix.put("semantic", "MODELVIEWINVERSETRANSPOSE");
		normalMatrix.put("type", 35675);
		parameters.set("normalMatrix", normalMatrix);

		ObjectNode position = objectMapper.createObjectNode();
		position.put("semantic", "POSITION");
		position.put("type", 35665);
		parameters.set("position", position);

		ObjectNode color = objectMapper.createObjectNode();
		color.put("semantic", "COLOR");
		color.put("type", FLOAT_VEC_4);
		parameters.set("color", color);

		ObjectNode projectionMatrix = objectMapper.createObjectNode();
		projectionMatrix.put("semantic", "PROJECTION");
		projectionMatrix.put("type", 35676);
		parameters.set("projectionMatrix", projectionMatrix);

//		ObjectNode shininess = objectMapper.createObjectNode();
//		shininess.put("type", 5126);
//		parameters.set("shininess", shininess);

//		ObjectNode specular = objectMapper.createObjectNode();
//		specular.put("type", 35666);
//		parameters.set("specular", specular);

		defaultTechnique.put("program", "defaultProgram");

		ArrayNode statesEnable = objectMapper.createArrayNode();
		statesEnable.add(2929);
		statesEnable.add(2884);
		states.set("enable", statesEnable);

//		uniforms.put("u_diffuse", "diffuse");
		uniforms.put("u_modelViewMatrix", "modelViewMatrix");
		uniforms.put("u_normalMatrix", "normalMatrix");
		uniforms.put("u_projectionMatrix", "projectionMatrix");
//		uniforms.put("u_shininess", "shininess");
//		uniforms.put("u_specular", "specular");

		defaultTechnique.set("attributes", attributes);
		defaultTechnique.set("parameters", parameters);
		defaultTechnique.set("states", states);
		defaultTechnique.set("uniforms", uniforms);

		techniques.set("defaultTechnique", defaultTechnique);

		return techniques;
	}

	private JsonNode createSkins() {
		// TODO
		return objectMapper.createObjectNode();
	}

	private JsonNode createShaders(String fragmentShaderBufferViewName, String vertexShaderBufferViewName) {
		ObjectNode shaders = objectMapper.createObjectNode();

		ObjectNode fragmentShaderExtensions = objectMapper.createObjectNode();
		ObjectNode fragmentShaderBinary = objectMapper.createObjectNode();
		fragmentShaderExtensions.set("KHR_binary_glTF", fragmentShaderBinary);
		fragmentShaderBinary.put("bufferView", fragmentShaderBufferViewName);

		ObjectNode fragmentShader = objectMapper.createObjectNode();
		fragmentShader.put("type", 35632);
		fragmentShader.put("uri", "data:,");
		fragmentShader.set("extensions", fragmentShaderExtensions);

		ObjectNode vertexShaderExtensions = objectMapper.createObjectNode();
		ObjectNode vertexShaderBinary = objectMapper.createObjectNode();
		vertexShaderExtensions.set("KHR_binary_glTF", vertexShaderBinary);
		vertexShaderBinary.put("bufferView", vertexShaderBufferViewName);

		ObjectNode vertexShader = objectMapper.createObjectNode();
		vertexShader.put("type", 35633);
		vertexShader.put("uri", "data:,");
		vertexShader.set("extensions", vertexShaderExtensions);

		shaders.set("fragmentShader", fragmentShader);
		shaders.set("vertexShader", vertexShader);

		return shaders;
	}

	private JsonNode createPrograms() {
		ObjectNode programs = objectMapper.createObjectNode();

		ObjectNode defaultProgram = objectMapper.createObjectNode();
		ArrayNode attributes = objectMapper.createArrayNode();
		programs.set("defaultProgram", defaultProgram);

		defaultProgram.set("attributes", attributes);
		attributes.add("a_normal");
		attributes.add("a_position");
		attributes.add("a_color");

		defaultProgram.put("fragmentShader", "fragmentShader");
		defaultProgram.put("vertexShader", "vertexShader");

		return programs;
	}

	private JsonNode createMeshes() {
		// TODO
		return objectMapper.createObjectNode();
	}

	private JsonNode createMaterials() {
		ObjectNode materialsNode = objectMapper.createObjectNode();

		ObjectNode defaultMaterial = objectMapper.createObjectNode();

		defaultMaterial.put("name", "default material");
		defaultMaterial.put("technique", "defaultTechnique");

		ObjectNode values = objectMapper.createObjectNode();

		ArrayNode diffuse = objectMapper.createArrayNode();
		diffuse.add(0.8000000119209291);
		diffuse.add(0);
		diffuse.add(0);
		diffuse.add(1);

		ArrayNode specular = objectMapper.createArrayNode();
		specular.add(0.20000000298023218);
		specular.add(0.20000000298023218);
		specular.add(0.20000000298023218);

		values.set("diffuse", diffuse);
		values.set("specular", specular);
		values.put("shininess", 256);

		defaultMaterial.set("values", values);

		materialsNode.set("defaultMaterial", defaultMaterial);

		return materialsNode;
	}

	private JsonNode createAsset() {
		// TODO
		return objectMapper.createObjectNode();
	}

	private void writeHeader(LittleEndianDataOutputStream dataOutputStream, int headerLength, int sceneLength, int bodyLength) throws IOException {
		dataOutputStream.write(MAGIC.getBytes(Charsets.US_ASCII));
		dataOutputStream.writeInt(FORMAT_VERSION_1);
		dataOutputStream.writeInt(headerLength + sceneLength + bodyLength);
		dataOutputStream.writeInt(sceneLength);
		dataOutputStream.writeInt(JSON_SCENE_FORMAT);
	}

	private void writeBody(LittleEndianDataOutputStream dataOutputStream, byte[] body) throws IOException {
		dataOutputStream.write(body);
	}

	private void writeScene(LittleEndianDataOutputStream dataOutputStream, byte[] scene) throws IOException {
		dataOutputStream.write(scene);
	}
}
