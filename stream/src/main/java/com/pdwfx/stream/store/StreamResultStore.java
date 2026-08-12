package com.pdwfx.stream.store;

import com.pdwfx.stream.model.StreamBatchResult;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

@Component
public class StreamResultStore {

    private final Map<String, StreamBatchResult> byId = new ConcurrentHashMap<>();
    private final CopyOnWriteArrayList<StreamBatchResult> recent = new CopyOnWriteArrayList<>();
    private static final int MAX_RECENT = 50;

    public void put(StreamBatchResult result) {
        if (result == null || result.getStreamBatchId() == null) return;
        byId.put(result.getStreamBatchId(), result);
        recent.add(0, result);
        while (recent.size() > MAX_RECENT) {
            recent.remove(recent.size() - 1);
        }
    }

    public StreamBatchResult get(String id) {
        return byId.get(id);
    }

    public List<StreamBatchResult> listRecent() {
        return Collections.unmodifiableList(new ArrayList<>(recent));
    }
}
