package com.example.bfhs;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class GenerateResponse {
  private String webhook;
  @JsonProperty("accessToken")
  private String accessToken;
}
