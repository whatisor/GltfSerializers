# GltfSerializers

This is mainly just a demo, but it might evolve into a useful glTF serializer with a bit more effort.

##

> Note: This plugin only works with the (yet unreleased) version 1.5 of BIMserver

## Implemented:
- https://github.com/KhronosGroup/glTF/blob/master/extensions/Khronos/KHR_binary_glTF/README.md
- Using vertex-colors where available, otherwise it defaults to a set of predefined type-colors

## Usage
- Install this plugin
- Download .glb files of your models with the "Binary glTF Serializer"

## Using with CesiumJS
- You can use a little bit of boilerplate code from the CesiumLoader project
- https://github.com/opensourceBIM/CesiumLoader

## TODO

- Convert to meters (the default length unit in glTF and Cesium) server-side
- Transparency does not seem to work (for window for example)
- Better default colors for objects with no vertex-colors
- Implement non-binary version as well
- Use object instancing
- Actually reuse vertices (requires a BIMserver change as well, vertices are now stored on a per-object basis). At the moment all object's vertices array are just concatenated into one big buffer (with separate views for each object).
- Do something with the normals in the shaders (maybe add some lights to the scene as well, not sure how that works in Cesium)


[NEW]
How to update parser manually
1. Open old and new .jar file with archive manager
2. Replace all .class files

[Partial loading with web server]
Run index.js of ThreeBIM or reimplement below feature for other web server:

const path = require('path');
const fs = require('fs');	
var os = require('os');
var url = require('url');
app.get('/gltfOPT', function(req, res, next) {
  //get type from 
  var url_parts = url.parse(req.url, true);
  var query = url_parts.query;
  //analysis root name
  var str = (os.tmpdir()) + "\\"+query.mode;
  console.log(str);
  //var stream = fs.createWriteStream(str+".gltfOPT");
  fs.writeFileSync(str+".gltfOPT", query.name?query.name:"");

  res.json("OK");
})
