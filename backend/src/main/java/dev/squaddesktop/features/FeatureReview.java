package dev.squaddesktop.features;

public record FeatureReview(String id, String featureId, int revision, String decision, String comment, String createdAt) {
}
