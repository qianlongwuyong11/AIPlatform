package com.bitguide.workflow.core;

/**
 * Channel controls how state values are merged when multiple nodes write to the same key.
 * Inspired by LangGraph's channel system.
 */
public interface Channel {
    /**
     * Merge incoming value with current value.
     * @param current  current value (may be null)
     * @param incoming new value being written
     * @return merged result
     */
    Object merge(Object current, Object incoming);
}
