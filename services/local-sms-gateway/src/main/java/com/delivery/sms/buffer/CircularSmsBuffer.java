package com.delivery.sms.buffer;

import com.delivery.sms.model.SmsMessage;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

@Component
public class CircularSmsBuffer {

    public static final int DEFAULT_CAPACITY = 1000;

    private final int capacity;
    private final SmsMessage[] buffer;
    private int head = 0;
    private int size = 0;
    private final Object lock = new Object();

    private final ConcurrentHashMap<String, Deque<SmsMessage>> phoneIndex = new ConcurrentHashMap<>();
    private final AtomicLong totalReceivedCounter = new AtomicLong(0);

    public CircularSmsBuffer() {
        this(DEFAULT_CAPACITY);
    }

    public CircularSmsBuffer(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("Buffer capacity must be strictly greater than 0");
        }
        this.capacity = capacity;
        this.buffer = new SmsMessage[capacity];
    }

    public void add(SmsMessage message) {
        synchronized (lock) {
            // 1. If buffer has reached capacity, evict the oldest message from phone index
            if (size == capacity) {
                SmsMessage evicted = buffer[head];
                if (evicted != null) {
                    Deque<SmsMessage> phoneQueue = phoneIndex.get(evicted.getPhone());
                    if (phoneQueue != null) {
                        phoneQueue.removeLastOccurrence(evicted);
                        if (phoneQueue.isEmpty()) {
                            phoneIndex.remove(evicted.getPhone());
                        }
                    }
                }
            } else {
                size++;
            }

            // 2. Insert new message at ring buffer head
            buffer[head] = message;
            head = (head + 1) % capacity;

            // 3. Index message under phone for O(1) instant retrieval
            phoneIndex.computeIfAbsent(message.getPhone(), k -> new ArrayDeque<>())
                    .addFirst(message);

            totalReceivedCounter.incrementAndGet();
        }
    }

    public Optional<SmsMessage> getLatest(String phone) {
        if (phone == null || phone.isBlank()) {
            return Optional.empty();
        }
        String normalizedPhone = SmsMessage.normalizePhone(phone);
        Deque<SmsMessage> queue = phoneIndex.get(normalizedPhone);
        if (queue == null) {
            return Optional.empty();
        }
        synchronized (lock) {
            return Optional.ofNullable(queue.peekFirst());
        }
    }

    public List<SmsMessage> getAll() {
        synchronized (lock) {
            List<SmsMessage> list = new ArrayList<>(size);
            for (int i = 0; i < size; i++) {
                int index = (head - 1 - i + capacity) % capacity;
                if (buffer[index] != null) {
                    list.add(buffer[index]);
                }
            }
            return list;
        }
    }

    public int size() {
        synchronized (lock) {
            return size;
        }
    }

    public int getCapacity() {
        return capacity;
    }

    public long getTotalReceived() {
        return totalReceivedCounter.get();
    }

    public int getDistinctPhonesCount() {
        return phoneIndex.size();
    }

    public void clear() {
        synchronized (lock) {
            for (int i = 0; i < capacity; i++) {
                buffer[i] = null;
            }
            head = 0;
            size = 0;
            phoneIndex.clear();
        }
    }
}
