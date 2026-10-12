package dev.squaddesktop.demo;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import dev.squaddesktop.runs.AgentEvent;
import dev.squaddesktop.runs.RunControl;
import dev.squaddesktop.runs.RunExecutor;
import dev.squaddesktop.runs.RunKind;
import dev.squaddesktop.runs.RunOutcome;
import dev.squaddesktop.runs.RunSpec;
import dev.squaddesktop.shared.Texts;

/**
 * Executor de demonstração: produz sempre a mesma sequência de eventos para o mesmo pedido, sem iniciar o
 * Claude Code, sem ler ou alterar arquivos e sem consumo de IA. Serve para conhecer e testar a interface.
 * Não informa custo nem tokens, porque não houve nenhum.
 */
public final class DemoRunExecutor implements RunExecutor {

    public static final String MODEL = "demonstração";

    private final Duration stepDelay;

    public DemoRunExecutor(Duration stepDelay) {
        this.stepDelay = stepDelay;
    }

    @Override
    public boolean demo() {
        return true;
    }

    @Override
    public RunOutcome execute(RunSpec spec, Consumer<AgentEvent> sink, RunControl control) {
        List<String> command = List.of("demonstração");
        sink.accept(new AgentEvent.Console("command", "modo demonstração: nenhum processo foi iniciado"));
        sink.accept(new AgentEvent.Started("demo-" + spec.runId(), MODEL, spec.cwd().toString(), spec.permissionMode(),
                spec.tools() == null ? List.of() : spec.tools(), null, spec.resumeSessionId() != null));
        if (!pause(control)) {
            return new RunOutcome(null, false, null, null, null, command);
        }

        sink.accept(new AgentEvent.ToolCall("demo-tool-1", "Read", Map.of("file_path", "README.md"), null));
        if (!pause(control)) {
            return new RunOutcome(null, false, null, null, null, command);
        }
        sink.accept(new AgentEvent.ToolResult("demo-tool-1", "Read", "(demonstração) nenhum arquivo foi lido de verdade", false, false));

        String text = reply(spec);
        StringBuilder sent = new StringBuilder();
        for (String word : text.split("(?<= )")) {
            if (!pause(control)) {
                return new RunOutcome(null, false, null, null, null, command);
            }
            sent.append(word);
            sink.accept(new AgentEvent.TextDelta(word));
        }
        sink.accept(new AgentEvent.Message(text, null, null));
        return RunOutcome.success(0, text, command);
    }

    static String reply(RunSpec spec) {
        String prompt = spec.prompt().strip();
        // o preâmbulo do modo vem antes da mensagem da pessoa, separado por uma linha em branco
        if (prompt.startsWith("[Modo desta mensagem") && prompt.contains("]\n\n")) {
            prompt = prompt.substring(prompt.indexOf("]\n\n") + 3);
        }
        if (prompt.startsWith("# Sala de planejamento")) {
            return DemoScripts.planning(spec.agentName(), prompt);
        }
        if (prompt.startsWith("# Feature ")) {
            return DemoScripts.featureStep(spec.agentName(), prompt);
        }
        String request = Texts.truncate(prompt.strip().replaceAll("\\s+", " "), 160);
        String action = spec.kind() == RunKind.EXECUTION
                ? "Numa execução real eu trabalharia na pasta do projeto e relataria os arquivos alterados e os testes rodados."
                : "Numa conversa real eu analisaria o projeto e responderia com base nos documentos e no código.";
        return "Resposta de demonstração de " + spec.agentName() + ". Recebi o pedido: \"" + request + "\". " + action
                + " Nada foi lido, alterado ou enviado a um serviço de IA.";
    }

    /** Espera um passo, acordando cedo se a execução for cancelada. @return false quando foi cancelada */
    private boolean pause(RunControl control) {
        long remaining = stepDelay.toMillis();
        while (remaining > 0 && !control.cancelled()) {
            long slice = Math.min(remaining, 25);
            try {
                Thread.sleep(slice);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
            remaining -= slice;
        }
        return !control.cancelled();
    }
}
