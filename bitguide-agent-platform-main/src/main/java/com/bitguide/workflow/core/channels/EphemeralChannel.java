package com.bitguide.workflow.core.channels;

import com.bitguide.workflow.core.Channel;

public class EphemeralChannel implements Channel {
    @Override
    public Object merge(Object current, Object incoming) {
        return incoming;
    }
}
