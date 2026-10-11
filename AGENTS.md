
# UGS PCB Tools — Project Instructions

## Base
- Upstream: winder/Universal-G-Code-Sender
- Main language: Java
- Target: UGS Platform
- Preserve upstream compatibility.

## Goals
Extend UGS with tools for PCB manufacturing.

### PCB Alignment
- Align double-sided PCBs using reference holes.
- Support XY translation and rotation.
- Start with two reference points.
- Later support 3–4 points and residual error analysis.
- Apply corrections to G-code safely.

### Adaptive Heightmap
- Support nonuniform probing points.
- Increase probing density where interpolation error is high.
- Reduce unnecessary measurements on flat regions.

### Adaptive Probing
- Predict surface height from previous measurements.
- Optimize safe Z retraction and probing intervals.
- Preserve collision avoidance and machine limits.

### Job Recovery
- Study the existing Run From feature.
- Identify coordinate and modal-state risks.

### Visualizer Smoothness
- Make the UGS Platform 3D visualizer feel closer to the UGS FX one.
- Start by raising the frame rate cap (currently 15 fps active, 4 fps idle).
- Keep CPU usage low while streaming G-code.
- Later evaluate replacing immediate-mode drawing with vertex buffers.

## Development Rules
- Inspect existing code before modifying it.
- Prefer reusable modules and minimal changes.
- Write automated tests for geometric algorithms.
- Do not send commands to real CNC hardware
  without explicit authorization.
- Explain important architectural decisions.
- Work incrementally and validate changes.
