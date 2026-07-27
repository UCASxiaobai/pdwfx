package com.pdwfx.signal.model;

import java.util.ArrayList;
import java.util.List;

public class NetworkAnalysisResponse {
    private int networkCount;
    private List<NetworkView> networks = new ArrayList<>();

    public int getNetworkCount() { return networkCount; }
    public void setNetworkCount(int networkCount) { this.networkCount = networkCount; }
    public List<NetworkView> getNetworks() { return networks; }
    public void setNetworks(List<NetworkView> networks) { this.networks = networks; }
}
