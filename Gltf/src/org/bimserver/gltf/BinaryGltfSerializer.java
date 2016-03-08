package org.bimserver.gltf;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.DoubleBuffer;

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

	private static final int ARRAY_BUFFER = 34962;
	private static final int ELEMENT_ARRAY_BUFFER = 34963;
	private static final String MAGIC = "glTF";
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
	private ArrayNode nodesNode;
	private ObjectNode sceneNode;
	private ArrayNode scenesNode;

	@Override
	protected boolean write(OutputStream outputStream, ProgressReporter progressReporter) throws SerializerException {
		sceneNode = objectMapper.createObjectNode();
		
		buffers = objectMapper.createObjectNode();
		meshes = objectMapper.createObjectNode();
		buffersViews = objectMapper.createObjectNode();
		scenesNode = objectMapper.createArrayNode();
		
		sceneNode.set("buffers", buffers);
		sceneNode.set("meshes", meshes);
		sceneNode.set("bufferViews", buffersViews);
		sceneNode.set("scenes", scenesNode);
		
		try {
			LittleEndianDataOutputStream dataOutputStream = new LittleEndianDataOutputStream(outputStream);

			byte[] body = generateBody();
			byte[] scene = generateScene();

			writeHeader(dataOutputStream, 20, scene.length, body.length);
			writeScene(dataOutputStream, scene);
			writeBody(dataOutputStream, scene);
		} catch (IOException e) {
			throw new SerializerException(e);
		}
		return false;
	}

	private byte[] generateScene() {
		ObjectNode rootNode = objectMapper.createObjectNode();

		int totalBodyByteLength = 0;
		int totalIndicesByteLength = 0;
		
		for (IfcProduct ifcProduct : model.getAll(IfcProduct.class)) {
			GeometryInfo geometryInfo = ifcProduct.getGeometry();
			if (geometryInfo != null) {
				GeometryData data = geometryInfo.getData();
				int byteLength = data.getIndices().length + data.getVertices().length + data.getNormals().length;
				totalIndicesByteLength += data.getIndices().length;
				totalBodyByteLength += byteLength;
			}
		}
		
		body = ByteBuffer.allocate(totalBodyByteLength);

		for (IfcProduct ifcProduct : model.getAll(IfcProduct.class)) {
			GeometryInfo geometryInfo = ifcProduct.getGeometry();
			if (geometryInfo != null) {
				body.put(geometryInfo.getData().getIndices());
			}
		}

		String indicesBufferView = createBufferView(totalIndicesByteLength, 0, ELEMENT_ARRAY_BUFFER);
		String restBufferView = createBufferView(totalBodyByteLength - totalIndicesByteLength, totalIndicesByteLength, ARRAY_BUFFER);
		
		scenesNode.add(createDefaultScene());

		for (IfcProduct ifcProduct : model.getAll(IfcProduct.class)) {
			GeometryInfo geometryInfo = ifcProduct.getGeometry();
			if (geometryInfo != null) {
				body.put(geometryInfo.getData().getVertices());
				body.put(geometryInfo.getData().getNormals());
				
				String indicesAccessor = addIndicesAccessor(ifcProduct, indicesBufferView);
				String verticesAccessor = addVerticesAccessor(ifcProduct, restBufferView);
				String normalsAccessor = addNormalsAccessor(ifcProduct, restBufferView);
				String meshName = addMesh(ifcProduct, indicesAccessor, verticesAccessor, normalsAccessor);
				
				((ArrayNode)sceneNode.get("nodes")).add(addNode(meshName, ifcProduct));
			}
		}
		
//		rootNode.set("buffers", createBuffers());
//		rootNode.set("accessors", createAccessors());
		rootNode.set("animations", createAnimations());
		rootNode.set("asset", createAsset());
//		rootNode.set("bufferViews", createBufferViews());
		rootNode.set("materials", createMaterials());
//		rootNode.set("meshes", createMeshes());
		rootNode.set("programs", createPrograms());
		rootNode.put("scene", "defaultScene");
		rootNode.set("shaders", createShaders());
		rootNode.set("skins", createSkins());
		rootNode.set("techniques", createTechniques());

		return rootNode.toString().getBytes(Charsets.UTF_8);
	}

	private ObjectNode addNode(String meshName, IfcProduct ifcProduct) {
		ObjectNode nodeNode = objectMapper.createObjectNode();
		
		ArrayNode matrixArray = objectMapper.createArrayNode();
		ByteBuffer matrixByteBuffer = ByteBuffer.wrap(ifcProduct.getGeometry().getTransformation());
		DoubleBuffer doubleBuffer = matrixByteBuffer.asDoubleBuffer();
		for (Double d : doubleBuffer.array()) {
			matrixArray.add(d);
		}
		
		ArrayNode childrenNode = objectMapper.createArrayNode();
		childrenNode.add(meshName);
		
		nodeNode.set("children", childrenNode);
		nodeNode.set("matrix", matrixArray);
		
		return nodeNode;
	}

	private ObjectNode createDefaultScene() {
		ObjectNode sceneNode = objectMapper.createObjectNode();
		
		nodesNode = objectMapper.createArrayNode();
		
		sceneNode.set("nodes", nodesNode);
		
		return sceneNode;
	}

	private String createBufferView(int byteLength, int byteOffset, int target) {
		String name = "bufferView_" + (bufferViewCounter++);
		ObjectNode bufferViewNode = objectMapper.createObjectNode();
		
		bufferViewNode.put("buffer", "binary_glTF");
		bufferViewNode.put("byteLength", byteLength);
		bufferViewNode.put("byteOffset", byteOffset);
		bufferViewNode.put("target", target);
		
		buffersViews.set(name, bufferViewNode);
		
		return name;
	}

	private String addNormalsAccessor(IfcProduct ifcProduct, String bufferViewName) {
		String accessorName = "accessor_normal_" + (accessorCounter++);
	
		ObjectNode accessor = objectMapper.createObjectNode();
		accessor.put("bufferView", bufferViewName);
		accessor.put("byteOffset", "");
		accessor.put("byteStride", "");
		accessor.put("componentType", "");
		accessor.put("count", "");
		accessor.put("type", "VEC3");
		
		ArrayNode min = objectMapper.createArrayNode();
		min.add(min[0]);
		min.add(min[1]);
		min.add(min[2]);
		ArrayNode max = objectMapper.createArrayNode();
		min.add(max[0]);
		min.add(max[1]);
		min.add(max[2]);
		
		accessors.set(accessorName, accessor);
		
		return accessorName;
	}

	private String addVerticesAccessor(IfcProduct ifcProduct, String bufferViewName) {
		String accessorName = "accessor_vertex_" + (accessorCounter++);
		
		ObjectNode accessor = objectMapper.createObjectNode();
		accessor.put("bufferView", bufferViewName);
		accessor.put("byteOffset", "");
		accessor.put("byteStride", "");
		accessor.put("componentType", "");
		accessor.put("count", "");
		accessor.put("type", "VEC3");
		
		ArrayNode min = objectMapper.createArrayNode();
		min.add(min[0]);
		min.add(min[1]);
		min.add(min[2]);
		ArrayNode max = objectMapper.createArrayNode();
		min.add(max[0]);
		min.add(max[1]);
		min.add(max[2]);
		
		accessors.set(accessorName, accessor);
		
		return accessorName;
	}

	private String addIndicesAccessor(IfcProduct ifcProduct, String bufferViewName) {
		String accessorName = "accessor_index_" + (accessorCounter++);
		
		ObjectNode accessor = objectMapper.createObjectNode();
    	accessor.put("bufferView", bufferViewName);
    	accessor.put("byteOffset", "");
    	accessor.put("byteStride", "");
    	accessor.put("componentType", "");
    	accessor.put("count", "");
    	accessor.put("type", "SCALAR");
		
    	accessors.set(accessorName, accessor);
    	
		return accessorName;
	}

	private String addMesh(IfcProduct ifcProduct, String indicesAccessor, String normalAccessor, String vertexAccessor) {
		ObjectNode meshNode = objectMapper.createObjectNode();
		String meshName = "mesh_" + ifcProduct.getOid();
		meshNode.put("name", meshName);
		ArrayNode primitivesNode = objectMapper.createArrayNode();
		ObjectNode primitiveNode = objectMapper.createObjectNode();
		
		ObjectNode attributesNode = objectMapper.createObjectNode();
		primitiveNode.set("attributes", attributesNode);
		attributesNode.put("NORMAL", normalAccessor);
		attributesNode.put("POSITION", vertexAccessor);
		
		primitiveNode.put("indices", indicesAccessor);
		primitiveNode.put("material", "Effect-Red"); // TODO
		primitiveNode.put("mode", 4); // TODO check
		
		primitivesNode.add(primitiveNode);
		meshNode.set("primitives", primitivesNode);
		meshes.set(meshName, meshNode);
		
		return meshName;
	}

	private void addBuffer(String name, String type, int byteLength) {
		ObjectNode bufferNode = objectMapper.createObjectNode();
		
		bufferNode.put("byteLength", byteLength);
		bufferNode.put("type", type);
		bufferNode.put("uri", null);
		
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
		// TODO
		return objectMapper.createObjectNode();
	}

	private JsonNode createSkins() {
		// TODO
		return objectMapper.createObjectNode();
	}

	private JsonNode createShaders() {
		// TODO
		return objectMapper.createObjectNode();
	}

	private JsonNode createPrograms() {
		// TODO
		return objectMapper.createObjectNode();
	}

	private JsonNode createMeshes() {
		// TODO
		return objectMapper.createObjectNode();
	}

	private JsonNode createMaterials() {
		// TODO
		return objectMapper.createObjectNode();
	}

	private JsonNode createBuffers() {
		// TODO
		return objectMapper.createObjectNode();
	}

	private JsonNode createBufferViews() {
		// TODO
		return objectMapper.createObjectNode();
	}

	private JsonNode createAsset() {
		// TODO
		return objectMapper.createObjectNode();
	}

	private byte[] generateBody() {
		return null;
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
