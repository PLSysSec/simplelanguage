/*
 * Copyright (c) 2012, 2018, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * The Universal Permissive License (UPL), Version 1.0
 *
 * Subject to the condition set forth below, permission is hereby granted to any
 * person obtaining a copy of this software, associated documentation and/or
 * data (collectively the "Software"), free of charge and under any and all
 * copyright rights in the Software, and any and all patent rights owned or
 * freely licensable by each licensor hereunder covering either (i) the
 * unmodified Software as contributed to or provided by such licensor, or (ii)
 * the Larger Works (as defined below), to deal in both
 *
 * (a) the Software, and
 *
 * (b) any piece of software and/or hardware listed in the lrgrwrks.txt file if
 * one is included with the Software each a "Larger Work" to which the Software
 * is contributed by such licensors),
 *
 * without restriction, including without limitation the rights to copy, create
 * derivative works of, display, perform, and distribute the Software and make,
 * use, sell, offer for sale, import, export, have made, and have sold the
 * Software and the Larger Work(s), and to sublicense the foregoing rights on
 * either these or other terms.
 *
 * This license is subject to the following condition:
 *
 * The above copyright notice and either this complete permission notice or at a
 * minimum a reference to the UPL must be included in all copies or substantial
 * portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package com.oracle.truffle.wasm.parser;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.antlr.v4.runtime.Parser;
import org.antlr.v4.runtime.Token;

import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.api.frame.FrameDescriptor;
import com.oracle.truffle.api.frame.FrameSlot;
import com.oracle.truffle.api.frame.FrameSlotKind;
import com.oracle.truffle.api.source.Source;
import com.oracle.truffle.api.source.SourceSection;
import com.oracle.truffle.wasm.WasmLanguage;
import com.oracle.truffle.wasm.nodes.WasmExpressionNode;
import com.oracle.truffle.wasm.nodes.WasmRootNode;
import com.oracle.truffle.wasm.nodes.WasmStatementNode;
import com.oracle.truffle.wasm.nodes.controlflow.WasmBlockNode;
import com.oracle.truffle.wasm.nodes.controlflow.WasmBreakNode;
import com.oracle.truffle.wasm.nodes.controlflow.WasmContinueNode;
import com.oracle.truffle.wasm.nodes.controlflow.WasmDebuggerNode;
import com.oracle.truffle.wasm.nodes.controlflow.WasmFunctionBodyNode;
import com.oracle.truffle.wasm.nodes.controlflow.WasmIfNode;
import com.oracle.truffle.wasm.nodes.controlflow.WasmReturnNode;
import com.oracle.truffle.wasm.nodes.controlflow.WasmWhileNode;
import com.oracle.truffle.wasm.nodes.expression.WasmAddNodeGen;
import com.oracle.truffle.wasm.nodes.expression.WasmBigIntegerLiteralNode;
import com.oracle.truffle.wasm.nodes.expression.WasmDivNodeGen;
import com.oracle.truffle.wasm.nodes.expression.WasmEqualNodeGen;
import com.oracle.truffle.wasm.nodes.expression.WasmFunctionLiteralNode;
import com.oracle.truffle.wasm.nodes.expression.WasmInvokeNode;
import com.oracle.truffle.wasm.nodes.expression.WasmLessOrEqualNodeGen;
import com.oracle.truffle.wasm.nodes.expression.WasmLessThanNodeGen;
import com.oracle.truffle.wasm.nodes.expression.WasmLogicalAndNode;
import com.oracle.truffle.wasm.nodes.expression.WasmLogicalNotNodeGen;
import com.oracle.truffle.wasm.nodes.expression.WasmLogicalOrNode;
import com.oracle.truffle.wasm.nodes.expression.WasmLongLiteralNode;
import com.oracle.truffle.wasm.nodes.expression.WasmMulNodeGen;
import com.oracle.truffle.wasm.nodes.expression.WasmParenExpressionNode;
import com.oracle.truffle.wasm.nodes.expression.WasmReadPropertyNode;
import com.oracle.truffle.wasm.nodes.expression.WasmReadPropertyNodeGen;
import com.oracle.truffle.wasm.nodes.expression.WasmStringLiteralNode;
import com.oracle.truffle.wasm.nodes.expression.WasmSubNodeGen;
import com.oracle.truffle.wasm.nodes.expression.WasmWritePropertyNode;
import com.oracle.truffle.wasm.nodes.expression.WasmWritePropertyNodeGen;
import com.oracle.truffle.wasm.nodes.local.WasmReadArgumentNode;
import com.oracle.truffle.wasm.nodes.local.WasmReadLocalVariableNode;
import com.oracle.truffle.wasm.nodes.local.WasmReadLocalVariableNodeGen;
import com.oracle.truffle.wasm.nodes.local.WasmWriteLocalVariableNode;
import com.oracle.truffle.wasm.nodes.local.WasmWriteLocalVariableNodeGen;
import com.oracle.truffle.wasm.nodes.util.WasmUnboxNodeGen;

/**
 * Helper class used by the Wasm {@link Parser} to create nodes. The code is factored out of the
 * automatically generated parser to keep the attributed grammar of Wasm small.
 */
public class WasmNodeFactory {

    /**
     * Local variable names that are visible in the current block. Variables are not visible outside
     * of their defining block, to prevent the usage of undefined variables. Because of that, we can
     * decide during parsing if a name references a local variable or is a function name.
     */
    static class LexicalScope {
        protected final LexicalScope outer;
        protected final Map<String, FrameSlot> locals;

        LexicalScope(LexicalScope outer) {
            this.outer = outer;
            this.locals = new HashMap<>();
            if (outer != null) {
                locals.putAll(outer.locals);
            }
        }
    }

    /* State while parsing a source unit. */
    private final Source source;
    private final Map<String, RootCallTarget> allFunctions;

    /* State while parsing a function. */
    private int functionStartPos;
    private String functionName;
    private int functionBodyStartPos; // includes parameter list
    private int parameterCount;
    private FrameDescriptor frameDescriptor;
    private List<WasmStatementNode> methodNodes;

    /* State while parsing a block. */
    private LexicalScope lexicalScope;
    private final WasmLanguage language;

    public WasmNodeFactory(WasmLanguage language, Source source) {
        this.language = language;
        this.source = source;
        this.allFunctions = new HashMap<>();
    }

    public Map<String, RootCallTarget> getAllFunctions() {
        return allFunctions;
    }

    public void startFunction(Token nameToken, Token bodyStartToken) {
        assert functionStartPos == 0;
        assert functionName == null;
        assert functionBodyStartPos == 0;
        assert parameterCount == 0;
        assert frameDescriptor == null;
        assert lexicalScope == null;

        functionStartPos = nameToken.getStartIndex();
        functionName = nameToken.getText();
        functionBodyStartPos = bodyStartToken.getStartIndex();
        frameDescriptor = new FrameDescriptor();
        methodNodes = new ArrayList<>();
        startBlock();
    }

    public void addFormalParameter(Token nameToken) {
        /*
         * Method parameters are assigned to local variables at the beginning of the method. This
         * ensures that accesses to parameters are specialized the same way as local variables are
         * specialized.
         */
        final WasmReadArgumentNode readArg = new WasmReadArgumentNode(parameterCount);
        WasmExpressionNode assignment = createAssignment(createStringLiteral(nameToken, false), readArg, parameterCount);
        methodNodes.add(assignment);
        parameterCount++;
    }

    public void finishFunction(WasmStatementNode bodyNode) {
        if (bodyNode == null) {
            // a state update that would otherwise be performed by finishBlock
            lexicalScope = lexicalScope.outer;
        } else {
            methodNodes.add(bodyNode);
            final int bodyEndPos = bodyNode.getSourceEndIndex();
            final SourceSection functionSrc = source.createSection(functionStartPos, bodyEndPos - functionStartPos);
            final WasmStatementNode methodBlock = finishBlock(methodNodes, functionBodyStartPos, bodyEndPos - functionBodyStartPos);
            assert lexicalScope == null : "Wrong scoping of blocks in parser";

            final WasmFunctionBodyNode functionBodyNode = new WasmFunctionBodyNode(methodBlock);
            functionBodyNode.setSourceSection(functionSrc.getCharIndex(), functionSrc.getCharLength());

            final WasmRootNode rootNode = new WasmRootNode(language, frameDescriptor, functionBodyNode, functionSrc, functionName);
            allFunctions.put(functionName, Truffle.getRuntime().createCallTarget(rootNode));
        }

        functionStartPos = 0;
        functionName = null;
        functionBodyStartPos = 0;
        parameterCount = 0;
        frameDescriptor = null;
        lexicalScope = null;
    }

    public void startBlock() {
        lexicalScope = new LexicalScope(lexicalScope);
    }

    public WasmStatementNode finishBlock(List<WasmStatementNode> bodyNodes, int startPos, int length) {
        lexicalScope = lexicalScope.outer;

        if (containsNull(bodyNodes)) {
            return null;
        }

        List<WasmStatementNode> flattenedNodes = new ArrayList<>(bodyNodes.size());
        flattenBlocks(bodyNodes, flattenedNodes);
        for (WasmStatementNode statement : flattenedNodes) {
            if (statement.hasSource() && !isHaltInCondition(statement)) {
                statement.addStatementTag();
            }
        }
        WasmBlockNode blockNode = new WasmBlockNode(flattenedNodes.toArray(new WasmStatementNode[flattenedNodes.size()]));
        blockNode.setSourceSection(startPos, length);
        return blockNode;
    }

    private static boolean isHaltInCondition(WasmStatementNode statement) {
        return (statement instanceof WasmIfNode) || (statement instanceof WasmWhileNode);
    }

    private void flattenBlocks(Iterable<? extends WasmStatementNode> bodyNodes, List<WasmStatementNode> flattenedNodes) {
        for (WasmStatementNode n : bodyNodes) {
            if (n instanceof WasmBlockNode) {
                flattenBlocks(((WasmBlockNode) n).getStatements(), flattenedNodes);
            } else {
                flattenedNodes.add(n);
            }
        }
    }

    /**
     * Returns an {@link WasmDebuggerNode} for the given token.
     *
     * @param debuggerToken The token containing the debugger node's info.
     * @return A WasmDebuggerNode for the given token.
     */
    WasmStatementNode createDebugger(Token debuggerToken) {
        final WasmDebuggerNode debuggerNode = new WasmDebuggerNode();
        srcFromToken(debuggerNode, debuggerToken);
        return debuggerNode;
    }

    /**
     * Returns an {@link WasmBreakNode} for the given token.
     *
     * @param breakToken The token containing the break node's info.
     * @return A WasmBreakNode for the given token.
     */
    public WasmStatementNode createBreak(Token breakToken) {
        final WasmBreakNode breakNode = new WasmBreakNode();
        srcFromToken(breakNode, breakToken);
        return breakNode;
    }

    /**
     * Returns an {@link WasmContinueNode} for the given token.
     *
     * @param continueToken The token containing the continue node's info.
     * @return A WasmContinueNode built using the given token.
     */
    public WasmStatementNode createContinue(Token continueToken) {
        final WasmContinueNode continueNode = new WasmContinueNode();
        srcFromToken(continueNode, continueToken);
        return continueNode;
    }

    /**
     * Returns an {@link WasmWhileNode} for the given parameters.
     *
     * @param whileToken The token containing the while node's info
     * @param conditionNode The conditional node for this while loop
     * @param bodyNode The body of the while loop
     * @return A WasmWhileNode built using the given parameters. null if either conditionNode or
     *         bodyNode is null.
     */
    public WasmStatementNode createWhile(Token whileToken, WasmExpressionNode conditionNode, WasmStatementNode bodyNode) {
        if (conditionNode == null || bodyNode == null) {
            return null;
        }

        conditionNode.addStatementTag();
        final int start = whileToken.getStartIndex();
        final int end = bodyNode.getSourceEndIndex();
        final WasmWhileNode whileNode = new WasmWhileNode(conditionNode, bodyNode);
        whileNode.setSourceSection(start, end - start);
        return whileNode;
    }

    /**
     * Returns an {@link WasmIfNode} for the given parameters.
     *
     * @param ifToken The token containing the if node's info
     * @param conditionNode The condition node of this if statement
     * @param thenPartNode The then part of the if
     * @param elsePartNode The else part of the if (null if no else part)
     * @return An WasmIfNode for the given parameters. null if either conditionNode or thenPartNode is
     *         null.
     */
    public WasmStatementNode createIf(Token ifToken, WasmExpressionNode conditionNode, WasmStatementNode thenPartNode, WasmStatementNode elsePartNode) {
        if (conditionNode == null || thenPartNode == null) {
            return null;
        }

        conditionNode.addStatementTag();
        final int start = ifToken.getStartIndex();
        final int end = elsePartNode == null ? thenPartNode.getSourceEndIndex() : elsePartNode.getSourceEndIndex();
        final WasmIfNode ifNode = new WasmIfNode(conditionNode, thenPartNode, elsePartNode);
        ifNode.setSourceSection(start, end - start);
        return ifNode;
    }

    /**
     * Returns an {@link WasmReturnNode} for the given parameters.
     *
     * @param t The token containing the return node's info
     * @param valueNode The value of the return (null if not returning a value)
     * @return An WasmReturnNode for the given parameters.
     */
    public WasmStatementNode createReturn(Token t, WasmExpressionNode valueNode) {
        final int start = t.getStartIndex();
        final int length = valueNode == null ? t.getText().length() : valueNode.getSourceEndIndex() - start;
        final WasmReturnNode returnNode = new WasmReturnNode(valueNode);
        returnNode.setSourceSection(start, length);
        return returnNode;
    }

    /**
     * Returns the corresponding subclass of {@link WasmExpressionNode} for binary expressions. </br>
     * These nodes are currently not instrumented.
     *
     * @param opToken The operator of the binary expression
     * @param leftNode The left node of the expression
     * @param rightNode The right node of the expression
     * @return A subclass of WasmExpressionNode using the given parameters based on the given opToken.
     *         null if either leftNode or rightNode is null.
     */
    public WasmExpressionNode createBinary(Token opToken, WasmExpressionNode leftNode, WasmExpressionNode rightNode) {
        if (leftNode == null || rightNode == null) {
            return null;
        }
        final WasmExpressionNode leftUnboxed = WasmUnboxNodeGen.create(leftNode);
        final WasmExpressionNode rightUnboxed = WasmUnboxNodeGen.create(rightNode);

        final WasmExpressionNode result;
        switch (opToken.getText()) {
            case "+":
                result = WasmAddNodeGen.create(leftUnboxed, rightUnboxed);
                break;
            case "*":
                result = WasmMulNodeGen.create(leftUnboxed, rightUnboxed);
                break;
            case "/":
                result = WasmDivNodeGen.create(leftUnboxed, rightUnboxed);
                break;
            case "-":
                result = WasmSubNodeGen.create(leftUnboxed, rightUnboxed);
                break;
            case "<":
                result = WasmLessThanNodeGen.create(leftUnboxed, rightUnboxed);
                break;
            case "<=":
                result = WasmLessOrEqualNodeGen.create(leftUnboxed, rightUnboxed);
                break;
            case ">":
                result = WasmLogicalNotNodeGen.create(WasmLessOrEqualNodeGen.create(leftUnboxed, rightUnboxed));
                break;
            case ">=":
                result = WasmLogicalNotNodeGen.create(WasmLessThanNodeGen.create(leftUnboxed, rightUnboxed));
                break;
            case "==":
                result = WasmEqualNodeGen.create(leftUnboxed, rightUnboxed);
                break;
            case "!=":
                result = WasmLogicalNotNodeGen.create(WasmEqualNodeGen.create(leftUnboxed, rightUnboxed));
                break;
            case "&&":
                result = new WasmLogicalAndNode(leftUnboxed, rightUnboxed);
                break;
            case "||":
                result = new WasmLogicalOrNode(leftUnboxed, rightUnboxed);
                break;
            default:
                throw new RuntimeException("unexpected operation: " + opToken.getText());
        }

        int start = leftNode.getSourceCharIndex();
        int length = rightNode.getSourceEndIndex() - start;
        result.setSourceSection(start, length);
        result.addExpressionTag();

        return result;
    }

    /**
     * Returns an {@link WasmInvokeNode} for the given parameters.
     *
     * @param functionNode The function being called
     * @param parameterNodes The parameters of the function call
     * @param finalToken A token used to determine the end of the sourceSelection for this call
     * @return An WasmInvokeNode for the given parameters. null if functionNode or any of the
     *         parameterNodes are null.
     */
    public WasmExpressionNode createCall(WasmExpressionNode functionNode, List<WasmExpressionNode> parameterNodes, Token finalToken) {
        if (functionNode == null || containsNull(parameterNodes)) {
            return null;
        }

        final WasmExpressionNode result = new WasmInvokeNode(functionNode, parameterNodes.toArray(new WasmExpressionNode[parameterNodes.size()]));

        final int startPos = functionNode.getSourceCharIndex();
        final int endPos = finalToken.getStartIndex() + finalToken.getText().length();
        result.setSourceSection(startPos, endPos - startPos);
        result.addExpressionTag();

        return result;
    }

    /**
     * Returns an {@link WasmWriteLocalVariableNode} for the given parameters.
     *
     * @param nameNode The name of the variable being assigned
     * @param valueNode The value to be assigned
     * @return An WasmExpressionNode for the given parameters. null if nameNode or valueNode is null.
     */
    public WasmExpressionNode createAssignment(WasmExpressionNode nameNode, WasmExpressionNode valueNode) {
        return createAssignment(nameNode, valueNode, null);
    }

    /**
     * Returns an {@link WasmWriteLocalVariableNode} for the given parameters.
     *
     * @param nameNode The name of the variable being assigned
     * @param valueNode The value to be assigned
     * @param argumentIndex null or index of the argument the assignment is assigning
     * @return An WasmExpressionNode for the given parameters. null if nameNode or valueNode is null.
     */
    public WasmExpressionNode createAssignment(WasmExpressionNode nameNode, WasmExpressionNode valueNode, Integer argumentIndex) {
        if (nameNode == null || valueNode == null) {
            return null;
        }

        String name = ((WasmStringLiteralNode) nameNode).executeGeneric(null);
        FrameSlot frameSlot = frameDescriptor.findOrAddFrameSlot(
                        name,
                        argumentIndex,
                        FrameSlotKind.Illegal);
        lexicalScope.locals.put(name, frameSlot);
        final WasmExpressionNode result = WasmWriteLocalVariableNodeGen.create(valueNode, frameSlot);

        if (valueNode.hasSource()) {
            final int start = nameNode.getSourceCharIndex();
            final int length = valueNode.getSourceEndIndex() - start;
            result.setSourceSection(start, length);
        }
        result.addExpressionTag();

        return result;
    }

    /**
     * Returns a {@link WasmReadLocalVariableNode} if this read is a local variable or a
     * {@link WasmFunctionLiteralNode} if this read is global. In Wasm, the only global names are
     * functions.
     *
     * @param nameNode The name of the variable/function being read
     * @return either:
     *         <ul>
     *         <li>A WasmReadLocalVariableNode representing the local variable being read.</li>
     *         <li>A WasmFunctionLiteralNode representing the function definition.</li>
     *         <li>null if nameNode is null.</li>
     *         </ul>
     */
    public WasmExpressionNode createRead(WasmExpressionNode nameNode) {
        if (nameNode == null) {
            return null;
        }

        String name = ((WasmStringLiteralNode) nameNode).executeGeneric(null);
        final WasmExpressionNode result;
        final FrameSlot frameSlot = lexicalScope.locals.get(name);
        if (frameSlot != null) {
            /* Read of a local variable. */
            result = WasmReadLocalVariableNodeGen.create(frameSlot);
        } else {
            /* Read of a global name. In our language, the only global names are functions. */
            result = new WasmFunctionLiteralNode(language, name);
        }
        result.setSourceSection(nameNode.getSourceCharIndex(), nameNode.getSourceLength());
        result.addExpressionTag();
        return result;
    }

    public WasmExpressionNode createStringLiteral(Token literalToken, boolean removeQuotes) {
        /* Remove the trailing and ending " */
        String literal = literalToken.getText();
        if (removeQuotes) {
            assert literal.length() >= 2 && literal.startsWith("\"") && literal.endsWith("\"");
            literal = literal.substring(1, literal.length() - 1);
        }

        final WasmStringLiteralNode result = new WasmStringLiteralNode(literal.intern());
        srcFromToken(result, literalToken);
        result.addExpressionTag();
        return result;
    }

    public WasmExpressionNode createNumericLiteral(Token literalToken) {
        WasmExpressionNode result;
        try {
            /* Try if the literal is small enough to fit into a long value. */
            result = new WasmLongLiteralNode(Long.parseLong(literalToken.getText()));
        } catch (NumberFormatException ex) {
            /* Overflow of long value, so fall back to BigInteger. */
            result = new WasmBigIntegerLiteralNode(new BigInteger(literalToken.getText()));
        }
        srcFromToken(result, literalToken);
        result.addExpressionTag();
        return result;
    }

    public WasmExpressionNode createParenExpression(WasmExpressionNode expressionNode, int start, int length) {
        if (expressionNode == null) {
            return null;
        }

        final WasmParenExpressionNode result = new WasmParenExpressionNode(expressionNode);
        result.setSourceSection(start, length);
        return result;
    }

    /**
     * Returns an {@link WasmReadPropertyNode} for the given parameters.
     *
     * @param receiverNode The receiver of the property access
     * @param nameNode The name of the property being accessed
     * @return An WasmExpressionNode for the given parameters. null if receiverNode or nameNode is
     *         null.
     */
    public WasmExpressionNode createReadProperty(WasmExpressionNode receiverNode, WasmExpressionNode nameNode) {
        if (receiverNode == null || nameNode == null) {
            return null;
        }

        final WasmExpressionNode result = WasmReadPropertyNodeGen.create(receiverNode, nameNode);

        final int startPos = receiverNode.getSourceCharIndex();
        final int endPos = nameNode.getSourceEndIndex();
        result.setSourceSection(startPos, endPos - startPos);
        result.addExpressionTag();

        return result;
    }

    /**
     * Returns an {@link WasmWritePropertyNode} for the given parameters.
     *
     * @param receiverNode The receiver object of the property assignment
     * @param nameNode The name of the property being assigned
     * @param valueNode The value to be assigned
     * @return An WasmExpressionNode for the given parameters. null if receiverNode, nameNode or
     *         valueNode is null.
     */
    public WasmExpressionNode createWriteProperty(WasmExpressionNode receiverNode, WasmExpressionNode nameNode, WasmExpressionNode valueNode) {
        if (receiverNode == null || nameNode == null || valueNode == null) {
            return null;
        }

        final WasmExpressionNode result = WasmWritePropertyNodeGen.create(receiverNode, nameNode, valueNode);

        final int start = receiverNode.getSourceCharIndex();
        final int length = valueNode.getSourceEndIndex() - start;
        result.setSourceSection(start, length);
        result.addExpressionTag();

        return result;
    }

    /**
     * Creates source description of a single token.
     */
    private static void srcFromToken(WasmStatementNode node, Token token) {
        node.setSourceSection(token.getStartIndex(), token.getText().length());
    }

    /**
     * Checks whether a list contains a null.
     */
    private static boolean containsNull(List<?> list) {
        for (Object e : list) {
            if (e == null) {
                return true;
            }
        }
        return false;
    }

}