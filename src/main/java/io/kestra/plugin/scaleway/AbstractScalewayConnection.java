package io.kestra.plugin.scaleway;

import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.Task;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.SuperBuilder;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
public abstract class AbstractScalewayConnection extends Task {
    @Schema(
        title = "Scaleway Secret Key",
        description = "The secret key for Scaleway IAM authentication. Recommended to store in Kestra secrets."
    )
    @NotNull
    @PluginProperty(group = "connection", secret = true)
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    protected Property<String> secretKey;

    @Schema(
        title = "Scaleway Project ID",
        description = "The Scaleway Project ID (UUID). Optional for Generative APIs."
    )
    @PluginProperty(group = "connection")
    protected Property<String> projectId;
}
