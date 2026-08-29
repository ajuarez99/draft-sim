package com.ballknowers.draftsim.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * effective = (n/(n+k)) * managerEstimate + (k/(n+k)) * leagueMean
 * With two drafts observed and k=4, a manager's own history carries 1/3 weight.
 */
@ConfigurationProperties(prefix = "draftsim.shrinkage")
public record ShrinkageProperties(double k) {

    public double shrink(double managerEstimate, double leagueMean, int draftsObserved) {
        double n = draftsObserved;
        double w = n / (n + k);
        return w * managerEstimate + (1 - w) * leagueMean;
    }
}
