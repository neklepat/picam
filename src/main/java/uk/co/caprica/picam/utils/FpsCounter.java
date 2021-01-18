package uk.co.caprica.picam.utils;

import java.util.ArrayList;
import java.util.List;

public class FpsCounter {

    private List<Long> sampleList = new ArrayList<>();

    private int sampleCount = 10;

    private long lastTime = System.nanoTime();
    private long sampleSum;

    public FpsCounter(int sampleCount) {
        if (sampleCount < 1) {
            this.sampleCount = 1;
        } else {
            this.sampleCount = sampleCount;
        }
    }

    public FpsCounter() {
        this.sampleCount = 10;
    }

    public float update() {
        long time = System.nanoTime();
        long diff = time - lastTime;
        lastTime = time;

        sampleSum += diff;

        sampleList.add(diff);
        if (sampleList.size() > sampleCount) {
            sampleSum -= sampleList.get(0);
            sampleList.remove(0);
        }

        long meanDiff = sampleSum / sampleList.size();
        return 1000000000 / meanDiff;
    }
}
