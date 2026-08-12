package com.pdwfx.stream.k187;

import java.util.ArrayList;
import java.util.List;

/**
 * 主峰 DOA 融合，逻辑照搬 cet36 {@code K187ReadPCDWDataServiceImpl#doaHistChiefZhang}。
 */
public final class DoaHistChiefZhang {

    private static final int MAX_DOA_AMOUNT = 4096;
    private static final short INVALID_VALUE = 4000;

    public static final class Result {
        public int code;
        public short doaMean = INVALID_VALUE;
        public short doaStd = INVALID_VALUE;
    }

    private DoaHistChiefZhang() {
    }

    public static Result fuse(List<Short> pDoaIn, long freqKhz, int rangeCountTh) {
        Result resultObj = new Result();
        if (pDoaIn == null) {
            resultObj.code = -1;
            return resultObj;
        }
        List<Short> pDoa = new ArrayList<>(pDoaIn);
        int doaAmount = pDoa.size();
        if (doaAmount < 5) {
            resultObj.code = -2;
            return resultObj;
        }
        if (doaAmount > MAX_DOA_AMOUNT) {
            resultObj.code = -3;
            return resultObj;
        }

        short[] doaBin = new short[3600];
        int[] doaHist = new int[3600];
        int[] pickUpDoa = new int[doaAmount];

        short doaRange;
        if (freqKhz < 108000) {
            doaRange = 140;
        } else if (freqKhz < 225000) {
            doaRange = 80;
        } else {
            doaRange = 50;
        }

        for (int i = 0; i < doaAmount; i++) {
            short v = (short) (pDoa.get(i) % 3600);
            if (v < 0) v = (short) (v + 3600);
            pDoa.set(i, v);
        }
        for (short value : pDoa) {
            doaBin[value]++;
        }
        for (int i = 0; i < 3600; i++) {
            for (int j = i - doaRange / 2; j <= i + doaRange / 2; j++) {
                int binIdx = j;
                if (binIdx < 0) binIdx += 3600;
                else if (binIdx >= 3600) binIdx -= 3600;
                doaHist[i] += doaBin[binIdx];
            }
        }

        int maxIdx = 0;
        int maxValue = 0;
        for (int i = 0; i < 3600; i++) {
            if (doaHist[i] > maxValue) {
                maxIdx = i;
                maxValue = doaHist[i];
            }
        }
        if (maxValue < rangeCountTh) {
            resultObj.code = -4;
            return resultObj;
        }

        int pickUpAmount = 0;
        boolean corrZeroFlag = false;
        for (int i = 0; i < doaAmount; i++) {
            int doaDiff = Math.abs(pDoa.get(i) - maxIdx);
            if (doaDiff <= doaRange / 2) {
                pickUpDoa[pickUpAmount++] = pDoa.get(i);
            } else if (doaDiff >= (3600 - doaRange / 2)) {
                pickUpDoa[pickUpAmount++] = pDoa.get(i);
                corrZeroFlag = true;
            }
        }
        if (pickUpAmount == 0) {
            resultObj.code = -5;
            return resultObj;
        }
        if (corrZeroFlag) {
            for (int i = 0; i < pickUpAmount; i++) {
                if (pickUpDoa[i] < 1800) pickUpDoa[i] += 3600;
            }
        }

        double mean = 0;
        for (int i = 0; i < pickUpAmount; i++) mean += pickUpDoa[i];
        mean /= pickUpAmount;
        double std = 0;
        for (int i = 0; i < pickUpAmount; i++) {
            double diff = pickUpDoa[i] - mean;
            std += diff * diff;
        }
        std = Math.sqrt(std / pickUpAmount);
        while (mean > 3600) mean -= 3600;
        while (mean < 0) mean += 3600;

        pickUpAmount = 0;
        corrZeroFlag = false;
        for (int i = 0; i < doaAmount; i++) {
            double doaDiff = Math.abs(pDoa.get(i) - mean);
            if (doaDiff <= 2 * std) {
                pickUpDoa[pickUpAmount++] = pDoa.get(i);
            } else if (doaDiff >= (3600 - 2 * std)) {
                pickUpDoa[pickUpAmount++] = pDoa.get(i);
                corrZeroFlag = true;
            }
        }
        if (corrZeroFlag) {
            for (int i = 0; i < pickUpAmount; i++) {
                if (pickUpDoa[i] < 1800) pickUpDoa[i] += 3600;
            }
        }
        if (pickUpAmount == 0) {
            resultObj.code = -6;
            return resultObj;
        }

        mean = 0;
        std = 0;
        for (int i = 0; i < pickUpAmount; i++) mean += pickUpDoa[i];
        mean /= pickUpAmount;
        for (int i = 0; i < pickUpAmount; i++) {
            double diff = pickUpDoa[i] - mean;
            std += diff * diff;
        }
        std = Math.sqrt(std / pickUpAmount);
        while (mean > 3600) mean -= 3600;
        while (mean < 0) mean += 3600;

        resultObj.doaMean = (short) mean;
        resultObj.doaStd = (short) std;
        resultObj.code = 0;
        return resultObj;
    }

    /** 相对方位转真方位（0.1°），course 为平台航向定点（÷100）。 */
    public static short toTrueAzimuth(short relativeAzimuth, int course) {
        double trueAz = (relativeAzimuth + course / 100.0) % 3600;
        if (trueAz < 0) trueAz += 3600;
        return (short) trueAz;
    }
}
