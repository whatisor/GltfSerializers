precision highp float;

varying vec3 v_normal;
varying vec4 v_color;
uniform vec4 u_diffuse;
uniform vec4 u_specular;
uniform float u_shininess;

void main(void) {
	vec3 normal = normalize(v_normal);
	vec4 color = v_color;
//	vec4 diffuse = vec4(0., 0., 0., 1.);
//	vec4 specular;
//	diffuse = u_diffuse;
//	specular = u_specular;
//	diffuse.xyz *= max(dot(normal,vec3(0.,0.,1.)), 0.);
//	color.xyz += diffuse.xyz;
//	color = vec4(color.rgb * diffuse.a, diffuse.a);
	color = v_color;
	gl_FragColor = color;
}