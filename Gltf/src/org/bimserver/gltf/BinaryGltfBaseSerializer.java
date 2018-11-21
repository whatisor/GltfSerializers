package org.bimserver.gltf;

import org.bimserver.models.geometry.GeometryData;
import org.bimserver.models.geometry.GeometryInfo;
import org.bimserver.models.ifc2x3tc1.IfcAnnotation;
import org.bimserver.models.ifc2x3tc1.IfcProduct;
import org.bimserver.plugins.serializers.EmfSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public abstract class BinaryGltfBaseSerializer extends EmfSerializer  {
	private static final Logger LOGGER = LoggerFactory.getLogger(BinaryGltfSerializer2.class);

	protected boolean checkGeometry(IfcProduct ifcProduct, boolean print) {
		String name = ifcProduct.eClass().getName();
		if (name.equals("IfcOpeningElement") || name.equals("IfcBuildingStorey") || name.equals("IfcBuilding")) {
			return false;
		}
		GeometryInfo geometryInfo = ifcProduct.getGeometry();
		if (geometryInfo == null) {
			if (ifcProduct instanceof IfcAnnotation) {
				return false;
			}
			if (print) {
				LOGGER.info("No GeometryInfo for " + name);
			}
			return false;
		}
		GeometryData geometryData = geometryInfo.getData();
		if (geometryData == null) {
			if (print) {
				LOGGER.info("No GeometryData for " + name);
			}
			return false;
		}
		if (geometryData.getVertices() == null) {
			if (print) {
				LOGGER.info("No Vertices for " + name);
			}
			return false;
		}
		return true;
	}
}
