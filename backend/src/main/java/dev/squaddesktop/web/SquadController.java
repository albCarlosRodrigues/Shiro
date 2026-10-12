package dev.squaddesktop.web;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import dev.squaddesktop.squads.Squad;
import dev.squaddesktop.squads.SquadDraft;
import dev.squaddesktop.squads.SquadService;

@RestController
@RequestMapping("/api/squads")
public class SquadController {

    private final SquadService squads;

    public SquadController(SquadService squads) {
        this.squads = squads;
    }

    @GetMapping
    public List<Squad> list(@RequestParam(defaultValue = "false") boolean archived) {
        return squads.list(archived);
    }

    @GetMapping("/{id}")
    public Squad get(@PathVariable String id) {
        return squads.get(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Squad create(@RequestBody SquadDraft draft) {
        return squads.create(draft);
    }

    @PutMapping("/{id}")
    public Squad update(@PathVariable String id, @RequestBody SquadDraft draft) {
        return squads.update(id, draft);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String id) {
        squads.delete(id);
    }
}
