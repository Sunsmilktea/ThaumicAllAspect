package com.sunmilktea.thaumicallaspect.aspect.scan;

/**
 * Facade class that delegates to AspectScanner for backward compatibility.
 * The actual scanning logic has been split into:
 * <ul>
 * <li>{@link AspectScanner} - Scan orchestration, index building, fluid scanning</li>
 * <li>{@link com.sunmilktea.thaumicallaspect.aspect.derive.AspectDeriver} - Core derivation logic (recipes, OreDict,
 * inheritance)</li>
 * <li>{@link com.sunmilktea.thaumicallaspect.aspect.derive.AspectFallback} - Keyword fallback, type derivation, special
 * rules</li>
 * <li>{@link com.sunmilktea.thaumicallaspect.aspect.derive.AspectUtils} - Shared state (caches, indexes) and utility
 * methods</li>
 * </ul>
 */
public class ItemBlockAspectHelper {

    public static void scanAndAssignAspects() {
        AspectScanner.scanAndAssignAspects();
    }
}
