package br.com.sampachat.api.controller;

import br.com.sampachat.api.model.Projeto;
import br.com.sampachat.api.service.SearchService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/search")
public class SearchController {

    @Autowired
    private SearchService searchService;

    @GetMapping
    public List<Projeto> searchProjects(
            @RequestParam("q") String query,
            @RequestParam(value = "mode", defaultValue = "aproximado") String mode) {

        if (query == null || query.isBlank()) {
            return List.of();
        }

        if ("exato".equalsIgnoreCase(mode)) {
            return searchService.searchFTS(query);
        } else {
            return searchService.searchSemantic(query);
        }
    }


}