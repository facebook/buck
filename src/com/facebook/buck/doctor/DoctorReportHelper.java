/*
 * Copyright 2016-present Facebook, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License. You may obtain
 * a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.facebook.buck.doctor;

import com.facebook.buck.doctor.config.DoctorConfig;
import com.facebook.buck.doctor.config.DoctorEndpointRequest;
import com.facebook.buck.doctor.config.DoctorEndpointResponse;
import com.facebook.buck.doctor.config.DoctorSuggestion;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.log.Logger;
import com.facebook.buck.model.Pair;
import com.facebook.buck.rage.BuildLogEntry;
import com.facebook.buck.rage.DefectSubmitResult;
import com.facebook.buck.rage.UserInput;
import com.facebook.buck.util.Console;
import com.facebook.buck.util.DirtyPrintStreamDecorator;
import com.facebook.buck.util.unit.SizeUnit;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Charsets;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Ordering;
import com.google.common.io.Files;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.TimeUnit;

import okhttp3.FormBody;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class DoctorReportHelper {

  private static final Logger LOG = Logger.get(DoctorReportHelper.class);
  private static final String WARNING_FILE_TEMPLATE = "Command %s does not contain a %s. Some " +
      "information will not be available";
  private static final String DECODE_FAIL_TEMPLATE = "Decoding remote response failed. Reason: %s";

  public static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

  private ProjectFilesystem filesystem;
  private UserInput input;
  private Console console;
  private ObjectMapper objectMapper;
  private DoctorConfig doctorConfig;

  public DoctorReportHelper(
      ProjectFilesystem filesystem,
      UserInput input,
      Console console,
      ObjectMapper objectMapper,
      DoctorConfig doctorConfig) {
    this.filesystem = filesystem;
    this.input = input;
    this.console = console;
    this.objectMapper = objectMapper;
    this.doctorConfig = doctorConfig;
  }

  public Optional<BuildLogEntry> promptForBuild(List<BuildLogEntry> buildLogs) throws IOException {
    if (buildLogs.isEmpty()) {
      return Optional.empty();
    }

    // Remove commands with unknown args or invocations of buck rage.
    buildLogs.removeIf(
        entry -> !(entry.getCommandArgs().isPresent() &&
            !entry.getCommandArgs().get().contains("rage") &&
            !entry.getCommandArgs().get().contains("doctor"))
    );

    if (buildLogs.isEmpty()) {
      return Optional.empty();
    }

    // Sort the remaining logs based on time, reverse order.
    Collections.sort(
        buildLogs,
        Ordering.natural().onResultOf(BuildLogEntry::getLastModifiedTime).reverse());

    return Optional.of(input.selectOne(
        "Which buck invocation would you like to report?",
        buildLogs,
        entry -> {
          Pair<Double, SizeUnit> humanReadableSize =
              SizeUnit.getHumanReadableSize(entry.getSize(), SizeUnit.BYTES);

          return String.format(
              "\t%s\tbuck [%s] %s (%.2f %s)",
              entry.getLastModifiedTime(),
              entry.getCommandArgs().orElse("unknown command"),
              prettyPrintExitCode(entry.getExitCode()),
              humanReadableSize.getFirst(),
              humanReadableSize.getSecond().getAbbreviation());
        }));
  }

  public DoctorEndpointRequest generateEndpointRequest(
      BuildLogEntry entry,
      Optional<DefectSubmitResult> rageResult)
      throws IOException {
    Optional<String> machineLog;
    Optional<String> rulekeyLog;
    Optional<String> rageUrl;

    if (entry.getMachineReadableLogFile().isPresent()) {
      machineLog = Optional.of(Files.toString(
          filesystem.resolve(entry.getMachineReadableLogFile().get()).toFile(),
          Charsets.UTF_8));
    } else {
      LOG.warn(String.format(WARNING_FILE_TEMPLATE, entry.toString(), "machine readable log"));
      machineLog = Optional.empty();
    }

    if (!entry.getRuleKeyLoggerLogFile().isPresent()) {
      LOG.warn(String.format(WARNING_FILE_TEMPLATE, entry.toString(), "rule key log"));
      rulekeyLog = Optional.empty();
    } else {
      rulekeyLog = Optional.of(Files.toString(
          entry.getRuleKeyLoggerLogFile().get().toFile(),
          Charsets.UTF_8));
    }

    rageUrl = rageResult.map(result -> result.getReportSubmitMessage().get());

    return DoctorEndpointRequest.of(
        entry.getBuildId(),
        entry.getRelativePath().toString(),
        machineLog,
        rulekeyLog,
        rageUrl);
  }

  public DoctorEndpointResponse uploadRequest(DoctorEndpointRequest request) {
    if (!doctorConfig.getEndpointUrl().isPresent()) {
      String errorMsg = String.format(
          "Doctor endpoint URL is not set. Please set [%s] %s on your .buckconfig",
          DoctorConfig.DOCTOR_SECTION,
          DoctorConfig.URL_FIELD);
      return createErrorDoctorEndpointResponse(errorMsg);
    }

    byte[] requestJson;
    try {
      requestJson = objectMapper.writeValueAsBytes(request);
    } catch (JsonProcessingException e) {
      return createErrorDoctorEndpointResponse("Failed to encode request to JSON. " +
          "Reason: " + e.getMessage());
    }

    OkHttpClient httpClient = new OkHttpClient.Builder()
        .connectTimeout(doctorConfig.getHttpTimeoutMs(), TimeUnit.MILLISECONDS)
        .readTimeout(doctorConfig.getHttpTimeoutMs(), TimeUnit.MILLISECONDS)
        .writeTimeout(doctorConfig.getHttpTimeoutMs(), TimeUnit.MILLISECONDS)
        .build();

    Response httpResponse;
    try {
      RequestBody requestBody;
      ImmutableMap<String, String> extraArgs = doctorConfig.getExtraRequestArgs();
      if (extraArgs.isEmpty()) {
        requestBody = RequestBody.create(JSON, requestJson);
      } else {
        FormBody.Builder formBody = new FormBody.Builder();
        formBody.add("data", new String(requestJson));
        for (Map.Entry<String, String> entry : extraArgs.entrySet()) {
          formBody.add(entry.getKey(), entry.getValue());
        }
        requestBody = formBody.build();
      }

      Request httpRequest = new Request.Builder()
          .url(doctorConfig.getEndpointUrl().get())
          .post(requestBody)
          .build();
      httpResponse = httpClient.newCall(httpRequest).execute();
    } catch (IOException e) {
      return createErrorDoctorEndpointResponse(
          "Failed to perform the request to " + doctorConfig.getEndpointUrl().get() +
              ". Reason: " + e.getMessage());
    }

    try {
      if (httpResponse.isSuccessful()) {
        String body = new String(httpResponse.body().bytes(), Charsets.UTF_8);
        return objectMapper.readValue(body, DoctorEndpointResponse.class);
      }
      return createErrorDoctorEndpointResponse("Request was not successful.");
    } catch (JsonProcessingException e) {
      return createErrorDoctorEndpointResponse(String.format(DECODE_FAIL_TEMPLATE, e.getMessage()));
    } catch (IOException e) {
      return createErrorDoctorEndpointResponse(String.format(DECODE_FAIL_TEMPLATE, e.getMessage()));
    }
  }

  public final void presentResponse(DoctorEndpointResponse response) {
    DirtyPrintStreamDecorator output = console.getStdOut();

    if (response.getErrorMessage().isPresent() && !response.getErrorMessage().get().isEmpty()) {
      LOG.warn(response.getErrorMessage().get());
      output.println(response.getErrorMessage().get());
      return;
    }

    if (response.getSuggestions().isEmpty()) {
      output.println(console.getAnsi().asWarningText("\n:: No available suggestions right now."));
    } else {
      output.println(console.getAnsi().asInformationText("\n:: Suggestions"));
      response.getSuggestions().forEach(this::prettyPrintSuggestion);
    }
    output.println();
  }

  public final void presentRageResult(Optional<DefectSubmitResult> result) {
    String reportLocation = result.get().getReportSubmitLocation();
    if (result.get().getUploadSuccess().isPresent()) {
      if (result.get().getUploadSuccess().get()) {
        console.getStdOut().print(result.get().getReportSubmitMessage().get());
      } else {
        console.getStdOut().printf(
            "Upload failed (%s)\nReport saved at %s\n",
            result.get().getReportSubmitErrorMessage().get(),
            reportLocation);
      }
    } else {
      console.getStdOut().printf("Report saved at %s\n", reportLocation);
    }
  }

  private void prettyPrintSuggestion(DoctorSuggestion suggestion) {
    console.getStdOut().println(String.format(
        "- [%s]%s %s",
        console.getAnsi().isAnsiTerminal()
            ? suggestion.getStatus().getEmoji() + " "
            : suggestion.getStatus().getText(),
        suggestion.getArea().isPresent() ? ("[" + suggestion.getArea().get() + "]") : "",
        suggestion.getSuggestion()));
  }

  private String prettyPrintExitCode(OptionalInt exitCode) {
    String result = "Exit code: " +
        (exitCode.isPresent() ? Integer.toString(exitCode.getAsInt()) : "Unknown");
    if (exitCode.isPresent() && console.getAnsi().isAnsiTerminal()) {
      if (exitCode.getAsInt() == 0) {
        return console.getAnsi().asGreenText(result);
      } else {
        return console.getAnsi().asRedText(result);
      }
    }
    return result;
  }

  private DoctorEndpointResponse createErrorDoctorEndpointResponse(String errorMessage) {
    console.printErrorText(errorMessage);
    LOG.error(errorMessage);
    return DoctorEndpointResponse.of(
        Optional.of(errorMessage),
        ImmutableList.of());
  }

  @VisibleForTesting
  Console getConsole() {
    return console;
  }

}
