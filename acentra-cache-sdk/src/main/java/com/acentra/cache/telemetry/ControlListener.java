package com.acentra.cache.telemetry;

/** Receives approved policy / tuning decisions polled from the telemetry service. */
@FunctionalInterface
public interface ControlListener {
    void onControl(ControlResponse response);
}
