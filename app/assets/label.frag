#version 300 es
// City-name label (premultiplied bitmap text), faded by uAlpha.
precision mediump float;
in vec2 vUv;
uniform sampler2D uTex;
uniform float uAlpha;
out vec4 fragColor;
void main() { fragColor = texture(uTex, vec2(vUv.x, 1.0 - vUv.y)) * uAlpha; }
