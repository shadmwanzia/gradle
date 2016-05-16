/*
 * Copyright 2016 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gradle.internal.logging.sink;

import net.jcip.annotations.ThreadSafe;
import org.gradle.api.Action;
import org.gradle.api.logging.LogLevel;
import org.gradle.api.logging.StandardOutputListener;
import org.gradle.api.logging.configuration.ConsoleOutput;
import org.gradle.internal.TrueTimeProvider;
import org.gradle.internal.event.ListenerBroadcast;
import org.gradle.internal.logging.LoggingOutputInternal;
import org.gradle.internal.logging.console.AnsiConsole;
import org.gradle.internal.logging.console.ColorMap;
import org.gradle.internal.logging.console.Console;
import org.gradle.internal.logging.console.ConsoleBackedProgressRenderer;
import org.gradle.internal.logging.console.DefaultColorMap;
import org.gradle.internal.logging.console.DefaultStatusBarFormatter;
import org.gradle.internal.logging.console.StyledTextOutputBackedRenderer;
import org.gradle.internal.logging.events.FlushToOutputsEvent;
import org.gradle.internal.logging.events.LogLevelChangeEvent;
import org.gradle.internal.logging.events.OutputEvent;
import org.gradle.internal.logging.events.OutputEventListener;
import org.gradle.internal.logging.source.LoggingConfigurer;
import org.gradle.internal.logging.text.StreamBackedStandardOutputListener;
import org.gradle.internal.logging.text.StreamingStyledTextOutput;
import org.gradle.internal.nativeintegration.console.ConsoleMetaData;
import org.gradle.internal.nativeintegration.console.FallbackConsoleMetaData;

import java.io.OutputStream;
import java.io.OutputStreamWriter;

/**
 * A {@link OutputEventListener} implementation which renders output events to various
 * destinations. This implementation is thread-safe.
 */
@ThreadSafe
public class OutputEventRenderer implements OutputEventListener, LoggingConfigurer, LoggingOutputInternal {
    private final ListenerBroadcast<OutputEventListener> stdOutAndErrorFormatters = new ListenerBroadcast<OutputEventListener>(OutputEventListener.class);
    private final ListenerBroadcast<OutputEventListener> formatters = new ListenerBroadcast<OutputEventListener>(OutputEventListener.class);
    private final ListenerBroadcast<StandardOutputListener> stdoutListeners = new ListenerBroadcast<StandardOutputListener>(StandardOutputListener.class);
    private final ListenerBroadcast<StandardOutputListener> stderrListeners = new ListenerBroadcast<StandardOutputListener>(StandardOutputListener.class);
    private final Object lock = new Object();
    private final DefaultColorMap colourMap = new DefaultColorMap();
    private LogLevel logLevel = LogLevel.LIFECYCLE;
    private final Action<? super OutputEventRenderer> consoleConfigureAction;
    private OutputStream originalStdOut;
    private OutputStream originalStdErr;
    private StreamBackedStandardOutputListener stdOutListener;
    private StreamBackedStandardOutputListener stdErrListener;
    private ConsoleOutput consoleOutput;

    public OutputEventRenderer() {
        OutputEventListener stdOutChain = onNonError(new ProgressLogEventGenerator(new StyledTextOutputBackedRenderer(new StreamingStyledTextOutput(stdoutListeners.getSource())), false));
        stdOutAndErrorFormatters.add(stdOutChain);
        OutputEventListener stdErrChain = onError(new ProgressLogEventGenerator(new StyledTextOutputBackedRenderer(new StreamingStyledTextOutput(stderrListeners.getSource())), false));
        stdOutAndErrorFormatters.add(stdErrChain);
        this.consoleConfigureAction = new ConsoleConfigureAction();
    }

    public ColorMap getColourMap() {
        return colourMap;
    }

    public ConsoleOutput getConsoleOutput() {
        return consoleOutput;
    }

    public OutputStream getOriginalStdOut() {
        return originalStdOut;
    }

    public OutputStream getOriginalStdErr() {
        return originalStdErr;
    }

    public void attachProcessConsole(ConsoleOutput consoleOutput) {
        synchronized (lock) {
            this.consoleOutput = consoleOutput;
            consoleConfigureAction.execute(this);
        }
    }

    public void attachAnsiConsole(OutputStream outputStream) {
        synchronized (lock) {
            OutputStreamWriter writer = new OutputStreamWriter(outputStream);
            Console console = new AnsiConsole(writer, writer, colourMap, true);
            addConsole(console, true, true, new FallbackConsoleMetaData());
        }
    }

    public void attachSystemOutAndErr() {
        addStandardOutputListener();
        addStandardErrorListener();
    }

    private void addStandardOutputListener() {
        synchronized (lock) {
            originalStdOut = System.out;
            if (stdOutListener != null) {
                stdoutListeners.remove(stdOutListener);
            }
            stdOutListener = new StreamBackedStandardOutputListener((Appendable) System.out);
            addStandardOutputListener(stdOutListener);
        }
    }

    private void addStandardErrorListener() {
        synchronized (lock) {
            originalStdErr = System.err;
            if(stdErrListener != null) {
                stderrListeners.remove(stdErrListener);
            }
            stdErrListener = new StreamBackedStandardOutputListener((Appendable) System.err);
            addStandardErrorListener(stdErrListener);
        }
    }

    public void removeStandardOutputAndError() {
        removeStandardOutputListener();
        removeStandardErrorListener();
    }

    private void removeStandardOutputListener() {
        synchronized (lock) {
            if (stdOutListener != null) {
                stdoutListeners.remove(stdOutListener);
                stdOutListener = null;
            }
        }
    }

    private void removeStandardErrorListener() {
        synchronized (lock) {
            if(stdErrListener != null) {
                stderrListeners.remove(stdErrListener);
                stdErrListener = null;
            }
        }
    }

    public void addOutputEventListener(OutputEventListener listener) {
        synchronized (lock) {
            formatters.add(listener);
        }
    }

    public void removeOutputEventListener(OutputEventListener listener) {
        synchronized (lock) {
            formatters.remove(listener);
        }
    }

    public OutputEventRenderer addConsole(Console console, boolean stdout, boolean stderr, ConsoleMetaData consoleMetaData) {
        final OutputEventListener consoleChain = new ConsoleBackedProgressRenderer(
                new ProgressLogEventGenerator(
                        new StyledTextOutputBackedRenderer(console.getMainArea()), true),
                console,
                new DefaultStatusBarFormatter(consoleMetaData),
                new TrueTimeProvider());
        synchronized (lock) {
            if (stdout && stderr) {
                formatters.add(consoleChain);
                removeStandardOutputAndError();
            } else if (stdout) {
                formatters.add(onNonError(consoleChain));
                removeStandardOutputListener();
            } else {
                formatters.add(onError(consoleChain));
                removeStandardErrorListener();
            }
            consoleChain.onOutput(new LogLevelChangeEvent(logLevel));
        }
        return this;
    }

    private OutputEventListener onError(final OutputEventListener listener) {
        return new OutputEventListener() {
            public void onOutput(OutputEvent event) {
                if (event.getLogLevel() == LogLevel.ERROR || event.getLogLevel() == null) {
                    listener.onOutput(event);
                }
            }
        };
    }

    private OutputEventListener onNonError(final OutputEventListener listener) {
        return new OutputEventListener() {
            public void onOutput(OutputEvent event) {
                if (event.getLogLevel() != LogLevel.ERROR || event.getLogLevel() == null) {
                    listener.onOutput(event);
                }
            }
        };
    }

    public void addStandardErrorListener(StandardOutputListener listener) {
        synchronized (lock) {
            stderrListeners.add(listener);
        }
    }

    public void addStandardOutputListener(StandardOutputListener listener) {
        synchronized (lock) {
            stdoutListeners.add(listener);
        }
    }

    public void addStandardOutputListener(OutputStream outputStream) {
        addStandardOutputListener(new StreamBackedStandardOutputListener(outputStream));
    }

    public void addStandardErrorListener(OutputStream outputStream) {
        addStandardErrorListener(new StreamBackedStandardOutputListener(outputStream));
    }


    public void removeStandardOutputListener(StandardOutputListener listener) {
        synchronized (lock) {
            stdoutListeners.remove(listener);
        }
    }

    public void removeStandardErrorListener(StandardOutputListener listener) {
        synchronized (lock) {
            stderrListeners.remove(listener);
        }
    }

    public void configure(LogLevel logLevel) {
        onOutput(new LogLevelChangeEvent(logLevel));
    }

    public void flush() {
        onOutput(new FlushToOutputsEvent());
    }

    public void onOutput(OutputEvent event) {
        synchronized (lock) {
            if (event.getLogLevel() != null && event.getLogLevel().compareTo(logLevel) < 0) {
                return;
            }
            if (event instanceof LogLevelChangeEvent) {
                LogLevelChangeEvent changeEvent = (LogLevelChangeEvent) event;
                LogLevel newLogLevel = changeEvent.getNewLogLevel();
                if (newLogLevel == this.logLevel) {
                    return;
                }
                this.logLevel = newLogLevel;
            }
            stdOutAndErrorFormatters.getSource().onOutput(event);
            formatters.getSource().onOutput(event);
        }
    }
}
