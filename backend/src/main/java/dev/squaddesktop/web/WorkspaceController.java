package dev.squaddesktop.web;

import java.util.List;
import java.util.Map;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import dev.squaddesktop.shared.JsonCodec;
import dev.squaddesktop.system.WorkspaceTransfer;

/** Exportação, importação e cópias de segurança do workspace. */
@RestController
@RequestMapping("/api")
public class WorkspaceController {

    private final WorkspaceTransfer transfer;
    private final JsonCodec json;

    public WorkspaceController(WorkspaceTransfer transfer, JsonCodec json) {
        this.transfer = transfer;
        this.json = json;
    }

    @GetMapping("/workspace/export")
    public ResponseEntity<String> export() {
        return ResponseEntity.ok().header(HttpHeaders.CONTENT_TYPE, "application/json; charset=utf-8").body(json.write(transfer.export()));
    }

    @PostMapping("/workspace/import/validate")
    public WorkspaceTransfer.ImportCheck validate(@RequestBody Map<String, Object> payload) {
        return transfer.validate(payload);
    }

    @PostMapping("/workspace/import")
    public WorkspaceTransfer.ImportResult importWorkspace(@RequestBody Map<String, Object> payload) {
        return transfer.importWorkspace(payload);
    }

    @GetMapping("/backups")
    public List<WorkspaceTransfer.BackupEntry> backups() {
        return transfer.backups();
    }

    @PostMapping("/backups")
    @ResponseStatus(HttpStatus.CREATED)
    public WorkspaceTransfer.BackupEntry snapshot() {
        return transfer.snapshot("manual");
    }
}
