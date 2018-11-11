package com.solrstart.refguide;

import org.asciidoctor.Asciidoctor;
import org.asciidoctor.ast.Document;
import org.asciidoctor.ast.StructuralNode;

import static org.asciidoctor.OptionsBuilder.options;

import java.io.File;
import java.util.*;

public class Indexer {

    private static TreeSet<String> CONTEXTS = new TreeSet<>();

    public static void main(String[] args) {
        File testFile = new File(args[0]);
        System.out.println("Testing with: " + testFile.getName());

        Asciidoctor asciidoctor = Asciidoctor.Factory.create();
        Map<String, Object> options = options().asMap();

        Document document = asciidoctor.loadFile(testFile, options);

        System.out.println(document.getDoctitle());

        printStructure(document.getBlocks(), 0);


        System.out.println("\n\n\nCONTEXTS:");
        System.out.println(CONTEXTS);
    }

    private static String OFFSET = "                                                         ";

    private static boolean printStructure(List<StructuralNode> nodes, int offsetLevel) {
        if (nodes == null) return false;

        for (StructuralNode node : nodes) {
            CONTEXTS.add(node.getContext()); //just for statistical purposes

            int level = node.getLevel();
//            String currentOffset = OFFSET.substring(0, (level * 4) + (isChild?2:0));
            String currentOffset = OFFSET.substring(0, offsetLevel * 4);
            System.out.printf("%s%d: %s (Style: %s, ContentModel: %s, Context: %s, Class: %s)\n",
                    currentOffset, level,
                    node.getId(), node.getStyle(), node.getContentModel(), node.getContext(),
                    node.getClass().getSimpleName()
                    );
//            System.out.printf("%svvvv\n%s\n%1$s^^^^\n", currentOffset, block.getContent());
            printStructure(node.getBlocks(), offsetLevel+1);
        }
        return true;
    }
}
