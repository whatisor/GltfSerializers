# GltfSerializers

This is mainly just a demo, but it might evolve into a useful glTF serializer with a bit more effort.

## TODO

- Convert to meters (the default length unit in glTF and Cesium) server-side
- Transparency does not seem to work (for window for example)
- Better default colors for objects with no vertex-colors
- Implement non-binary version as well
- Use object instancing
- Actually reuse vertices (requires a BIMserver change as well, vertices are now stored on a per-object basis). At the moment all object's vertices array are just concatenated into one big buffer (with separate views for each object).
