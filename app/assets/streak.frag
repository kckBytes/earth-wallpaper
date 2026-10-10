#version 300 es
precision mediump float;
in float vA;
uniform float uAlpha;
out vec4 fragColor;
void main() { float a = vA * uAlpha; fragColor = vec4(vec3(a), a); }       // premultiplied white
