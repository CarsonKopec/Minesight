package com.minesight;

/**
 * One detected object. Coordinates are in capture-frame pixels (the Minecraft
 * window client area); x/y is the box center, per the YOLO convention.
 * Field names match the JSON protocol for direct Gson mapping.
 */
public class Detection {
    public String label;
    public float x;
    public float y;
    public float w;
    public float h;
    public float confidence;
    public int id;
}
