package com.example.airportagent.domain;

/**
 * The part of a score that one component gives.
 * The points of all contributions add up to the score.
 *
 * @param componentValue the component value from 0 to 100
 * @param weight         the weight after the weights of missing components are shared
 * @param points         componentValue times weight
 * @param usesProxy      true if a KPI of this component is a proxy
 */
public record Contribution(Component component, double componentValue, double weight, double points,
                           boolean usesProxy) {}
