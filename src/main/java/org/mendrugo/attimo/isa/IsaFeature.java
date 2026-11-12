package org.mendrugo.attimo.isa;

import java.util.List;

public record IsaFeature(String name, String description, String architecture,
        List<String> families) {}
