package com.bitguide.workflow.core.channels;

import com.bitguide.workflow.core.Channel;

import java.util.ArrayList;
import java.util.List;

public class AppendChannel implements Channel {
    @Override
    @SuppressWarnings("unchecked")
    public Object merge(Object current, Object incoming) {
        List<Object> list = current != null ? new ArrayList<>((List<Object>) current) : new ArrayList<>();
        if (incoming instanceof List) {
            list.addAll((List<Object>) incoming);
        } else {
            list.add(incoming);
        }
        return list;
    }
}
