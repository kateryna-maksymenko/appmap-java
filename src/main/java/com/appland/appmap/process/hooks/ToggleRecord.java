package com.appland.appmap.process.hooks;

import static com.appland.appmap.util.StringUtil.decapitalize;
import static com.appland.appmap.util.StringUtil.identifierToSentence;

import com.appland.appmap.output.v1.Event;
import com.appland.appmap.process.ExitEarly;
import com.appland.appmap.record.ActiveSessionException;
import com.appland.appmap.record.IRecordingSession;
import com.appland.appmap.record.Recorder;
import com.appland.appmap.reflect.HttpServletRequest;
import com.appland.appmap.reflect.HttpServletResponse;
import com.appland.appmap.reflect.FilterChain;
import com.appland.appmap.transform.annotations.*;
import com.appland.appmap.util.Logger;

import java.io.IOException;
import java.io.PrintWriter;

/**
 * Hooks to toggle event recording. This could be either via HTTP or by entering a unit test method.
 */
public class ToggleRecord {
  private static final Recorder recorder = Recorder.getInstance();
  public static final String RecordRoute = "/_appmap/record";

  private static void doDelete(HttpServletRequest req, HttpServletResponse res) {
    try {
      String json = recorder.stop();
      res.setContentType("application/json");
      res.setContentLength(json.length());

      PrintWriter writer = res.getWriter();
      writer.write(json);
      writer.flush();
    } catch (ActiveSessionException e) {
      res.setStatus(HttpServletResponse.SC_NOT_FOUND);
    } catch (IOException e) {
      Logger.printf("failed to write response: %s\n", e.getMessage());
    }
  }

  private static void doGet(HttpServletRequest req, HttpServletResponse res) {
    res.setStatus(HttpServletResponse.SC_OK);

    String responseJson = String.format("{\"enabled\":%b}", recorder.hasActiveSession());
    res.setContentType("application/json");
    res.setContentLength(responseJson.length());

    try {
      PrintWriter writer = res.getWriter();
      writer.write(responseJson);
      writer.flush();
    } catch (IOException e) {
      Logger.printf("failed to write response: %s\n", e.getMessage());
    }
  }

  private static void doPost(HttpServletRequest req, HttpServletResponse res) {
    IRecordingSession.Metadata metadata = new IRecordingSession.Metadata();
    metadata.recorderName = "remote_recording";
    try {
      recorder.start(metadata);
    } catch (ActiveSessionException e) {
      res.setStatus(HttpServletResponse.SC_CONFLICT);
    }
  }

  private static void service(Object[] args) throws ExitEarly {
    if (args.length != 2) {
      return;
    }

    final HttpServletRequest req = new HttpServletRequest(args[0]);
    if (!req.getRequestURI().endsWith(RecordRoute)) {
      return;
    }

    final HttpServletResponse res = new HttpServletResponse(args[1]);
    if (req.getMethod().equals("GET")) {
      doGet(req, res);
    } else if (req.getMethod().equals("POST")) {
      doPost(req, res);
    } else if (req.getMethod().equals("DELETE")) {
      doDelete(req, res);
    }

    throw new ExitEarly();
  }

  private static void skipFilterChain(Object[] args) throws ExitEarly {
    if (args.length != 3) {
      return;
    }

    final HttpServletRequest req = new HttpServletRequest(args[0]);
    if (!req.getRequestURI().endsWith(RecordRoute)) {
      return;
    }

    final FilterChain chain = new FilterChain(args[2]);
    chain.doFilter(args[0], args[1]);

    throw new ExitEarly();
  }

  @ExcludeReceiver
  @ArgumentArray
  @HookClass("javax.servlet.http.HttpServlet")
  public static void service(Event event, Object[] args) throws ExitEarly {
    service(args);
  }

  @ExcludeReceiver
  @ArgumentArray
  @HookClass(value = "jakarta.servlet.http.HttpServlet", method = "service")
  public static void serviceJakarta(Event event, Object[] args) throws ExitEarly {
    service(args);
  }

  @ContinueHooking
  @ExcludeReceiver
  @ArgumentArray
  @HookClass("javax.servlet.Filter")
  public static void doFilter(Event event, Object[] args) throws ExitEarly {
    skipFilterChain(args);
  }

  @ContinueHooking
  @ExcludeReceiver
  @ArgumentArray
  @HookClass(value = "jakarta.servlet.Filter", method = "doFilter")
  public static void doFilterJakarta(Event event, Object[] args) throws ExitEarly {
    skipFilterChain(args);
  }

  private static void startTest(Event event) {
    try {
      final String fileName = String.join("_", event.definedClass, event.methodId)
          .replaceAll("[^a-zA-Z0-9-_]", "_");

      IRecordingSession.Metadata metadata = new IRecordingSession.Metadata();

      // TODO: Obtain this info in the constructor
      boolean junit = false;
      StackTraceElement[] stack = Thread.currentThread().getStackTrace();
      for (int i = 0; !junit && i < stack.length; i++) {
        if (stack[i].getClassName().startsWith("org.junit")) {
          junit = true;
        }
      }

      metadata.feature = identifierToSentence(event.methodId);
      metadata.featureGroup = identifierToSentence(event.definedClass);
      metadata.scenarioName = String.format(
        "%s %s",
        metadata.featureGroup,
        decapitalize(metadata.feature));
      metadata.recordedClassName = event.definedClass;
      metadata.recordedMethodName = event.methodId;
      if (junit) {
        metadata.recorderName = "toggle_record_receiver";
        metadata.framework = "junit";
      }

      recorder.start(fileName, metadata);
    } catch (ActiveSessionException e) {
      Logger.printf("%s\n", e.getMessage());
    }
  }

  private static void stopTest() {
    try {
      recorder.stop();
    } catch (ActiveSessionException e) {
      Logger.printf("%s\n", e.getMessage());
    }
  }

  @ArgumentArray
  @ExcludeReceiver
  @HookAnnotated("org.junit.Test")
  public static void junit(Event event, Object[] args) {
    startTest(event);
  }

  @ArgumentArray
  @CallbackOn(MethodEvent.METHOD_RETURN)
  @ExcludeReceiver
  @HookAnnotated("org.junit.Test")
  public static void junit(Event event, Object returnValue, Object[] args) {
    stopTest();
  }

  @ArgumentArray
  @CallbackOn(MethodEvent.METHOD_EXCEPTION)
  @ExcludeReceiver
  @HookAnnotated("org.junit.Test")
  public static void junit(Event event, Exception exception, Object[] args) {
    event.setException(exception);
    recorder.add(event);
    stopTest();
  }

  @ArgumentArray
  @ExcludeReceiver
  @HookAnnotated("org.junit.jupiter.api.Test")
  public static void junitJupiter(Event event, Object[] args) {
    startTest(event);
  }

  @ArgumentArray
  @CallbackOn(MethodEvent.METHOD_RETURN)
  @ExcludeReceiver
  @HookAnnotated("org.junit.jupiter.api.Test")
  public static void junitJupiter(Event event, Object returnValue, Object[] args) {
    stopTest();
  }

  @ArgumentArray
  @CallbackOn(MethodEvent.METHOD_EXCEPTION)
  @ExcludeReceiver
  @HookAnnotated("org.junit.jupiter.api.Test")
  public static void junitJupiter(Event event, Exception exception, Object[] args) {
    event.setException(exception);
    recorder.add(event);
    stopTest();
  }
}
