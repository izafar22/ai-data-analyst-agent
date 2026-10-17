package com.example.analyst.metadata;

import java.util.List;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/schema/tables")
class SchemaController {
    private final SchemaService schemas;
    SchemaController(SchemaService schemas) { this.schemas = schemas; }

    @GetMapping
    List<SchemaService.Table> tables() { return schemas.tables(); }

    @GetMapping("/{name}")
    SchemaService.Table table(@PathVariable String name) { return schemas.table(name); }
}
