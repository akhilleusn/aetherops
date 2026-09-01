package com.aetherops.statuspage.model;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.List;

@Data
@Builder
public class PublicStatusPageResponse {

    private String pageName;
    private ComponentStatus overallStatus;
    private List<PublicComponent> components;
    private List<PublicIncident> incidents;
    private Branding branding;

    @Data
    @Builder
    public static class Branding {
        private String companyName;
        private String logoUrl;
        private String primaryColor;
        private String secondaryColor;
    }

    @Data
    @Builder
    public static class PublicComponent {
        private String name;
        private ComponentStatus status;
    }

    @Data
    @Builder
    public static class PublicIncident {
        private String title;
        private boolean resolved;
        private List<PublicUpdate> updates;
    }

    @Data
    @Builder
    public static class PublicUpdate {
        private StatusPageUpdateStatus status;
        private String message;
        private Instant postedAt;
    }
}
