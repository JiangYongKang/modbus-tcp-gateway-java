package com.github.highcumontoa.gateway.fault;

import com.github.highcumontoa.gateway.model.RegisterType;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 运行期故障编排注册表。本地数据源在每次响应前查询命中的规则；
 * 规则可随时注入、按 id 撤销或一键清空，网关本身不重启。
 */
@Component
public class FaultRegistry {
    private final ConcurrentHashMap<String, Entry> entries = new ConcurrentHashMap<>();
    private final CopyOnWriteArrayList<String> insertionOrder = new CopyOnWriteArrayList<>();
    private final AtomicLong sequence = new AtomicLong();

    public FaultSpec inject(FaultSpec spec) {
        String id = spec.id() == null || spec.id().isBlank()
                ? "fault-" + sequence.incrementAndGet() : spec.id();
        FaultSpec withId = new FaultSpec(id, spec.registerType(), spec.address(), spec.span(),
                spec.type(), spec.delayMs(), spec.exceptionCode(), spec.chunkSize(),
                spec.chunkDelayMs(), spec.times());
        entries.put(id, new Entry(withId));
        if (!insertionOrder.contains(id)) {
            insertionOrder.add(id);
        }
        return withId;
    }

    public boolean revoke(String id) {
        Entry removed = entries.remove(id);
        insertionOrder.remove(id);
        return removed != null;
    }

    public void clear() {
        entries.clear();
        insertionOrder.clear();
    }

    public List<FaultSpec> list() {
        return insertionOrder.stream()
                .map(entries::get)
                .filter(java.util.Objects::nonNull)
                .map(Entry::spec)
                .toList();
    }

    /**
     * 查找命中规则并消耗一次使用次数。返回空表示本次请求正常处理。
     */
    public Optional<FaultSpec> claim(RegisterType registerType, int startAddress, int quantity) {
        for (String id : insertionOrder) {
            Entry entry = entries.get(id);
            if (entry == null || !entry.spec().matches(registerType, startAddress, quantity)) {
                continue;
            }
            if (entry.claim()) {
                return Optional.of(entry.spec());
            }
        }
        return Optional.empty();
    }

    private static final class Entry {
        private final FaultSpec spec;
        private final AtomicLong remaining;

        private Entry(FaultSpec spec) {
            this.spec = spec;
            this.remaining = new AtomicLong(spec.times());
        }

        private FaultSpec spec() {
            return spec;
        }

        private synchronized boolean claim() {
            if (spec.times() == FaultSpec.UNLIMITED) {
                return true;
            }
            long left = remaining.get();
            if (left <= 0) {
                return false;
            }
            remaining.decrementAndGet();
            return true;
        }
    }
}
