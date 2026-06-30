/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.facebook.ktfmt.format

import com.google.common.collect.Range
import com.google.common.collect.RangeSet
import com.google.common.collect.TreeRangeSet
import org.jetbrains.kotlin.com.intellij.psi.PsiElement
import org.jetbrains.kotlin.com.intellij.psi.PsiWhiteSpace
import org.jetbrains.kotlin.kdoc.psi.impl.KDocImpl
import org.jetbrains.kotlin.psi.KtElement
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtImportList
import org.jetbrains.kotlin.psi.KtPackageDirective
import org.jetbrains.kotlin.psi.KtReferenceExpression
import org.jetbrains.kotlin.psi.KtTreeVisitorVoid
import org.jetbrains.kotlin.psi.psiUtil.endOffset
import org.jetbrains.kotlin.psi.psiUtil.startOffset

/**
 * Adds and removes elements that are not strictly needed in the code, such as semicolons and unused
 * imports.
 */
object RedundantElementManager {
  /** Remove extra semicolons and unused imports, if enabled in the [options] */
  internal fun dropRedundantElements(file: KtFile, options: FormattingOptions): String {
    val code = file.text
    val redundantImportDetector = RedundantImportDetector(enabled = options.removeUnusedImports)
    val redundantSemicolonDetector = RedundantSemicolonDetector()
    val trailingCommaDetector = TrailingCommas.Detector()

    file.accept(
        object : KtTreeVisitorVoid() {
          override fun visitElement(element: PsiElement) {
            if (element is KDocImpl) {
              redundantImportDetector.takeKdoc(element)
              return
            }

            redundantSemicolonDetector.takeElement(element)
            if (options.trailingCommaManagementStrategy.removeRedundantTrailingCommas) {
              trailingCommaDetector.takeElement(element)
            }
            super.visitElement(element)
          }

          override fun visitPackageDirective(directive: KtPackageDirective) {
            redundantImportDetector.takePackageDirective(directive) {
              super.visitPackageDirective(directive)
            }
          }

          override fun visitImportList(importList: KtImportList) {
            redundantImportDetector.takeImportList(importList) { super.visitImportList(importList) }
          }

          override fun visitReferenceExpression(expression: KtReferenceExpression) {
            redundantImportDetector.takeReferenceExpression(expression)
            super.visitReferenceExpression(expression)
          }
        }
    )

    val elementsToRemove =
        redundantSemicolonDetector.getRedundantSemicolonElements() +
            redundantImportDetector.getRedundantImportElements() +
            trailingCommaDetector.getTrailingCommaElements()
    if (elementsToRemove.isEmpty()) return code
    val result = StringBuilder(code)

    for (element in elementsToRemove.sortedByDescending(PsiElement::endOffset)) {
      // Don't insert extra newlines when the semicolon is already a line terminator
      val replacement =
          if (element.text == ";" && !element.nextSibling.containsNewline()) {
            "\n"
          } else {
            ""
          }
      result.replace(element.startOffset, element.endOffset, replacement)
    }

    return result.toString()
  }

  internal fun addRedundantElements(file: KtFile, options: FormattingOptions): String =
      addRedundantElementsWithRanges(file, options).code

  /**
   * Result of [addRedundantElementsWithRanges]: the [code] with trailing commas inserted, and the
   * character ranges in [code] each inserted comma occupies (so a caller can re-layout only those
   * spots).
   */
  internal class TrailingCommaResult(val code: String, val insertedCommaRanges: RangeSet<Int>)

  internal fun addRedundantElementsWithRanges(
      file: KtFile,
      options: FormattingOptions,
  ): TrailingCommaResult {
    val code = file.text
    if (!options.manageTrailingCommas) {
      return TrailingCommaResult(code, TreeRangeSet.create())
    }

    val trailingCommaSuggestor = TrailingCommas.Suggestor()
    file.accept(
        object : KtTreeVisitorVoid() {
          override fun visitKtElement(element: KtElement) {
            trailingCommaSuggestor.takeElement(element)
            super.visitElement(element)
          }
        }
    )

    val offsets =
        trailingCommaSuggestor.getTrailingCommaSuggestions().map(PsiElement::endOffset).sorted()
    if (offsets.isEmpty()) return TrailingCommaResult(code, TreeRangeSet.create())

    val result = StringBuilder(code)
    for (offset in offsets.asReversed()) {
      result.insert(offset, ',')
    }

    // Inserting at ascending offsets o_i (done in reverse above), the i-th comma ends up at
    // o_i + i in the final text, since i commas were inserted before it.
    val insertedCommaRanges = TreeRangeSet.create<Int>()
    offsets.forEachIndexed { i, offset ->
      insertedCommaRanges.add(Range.closedOpen(offset + i, offset + i + 1))
    }
    return TrailingCommaResult(result.toString(), insertedCommaRanges)
  }

  private fun PsiElement?.containsNewline(): Boolean {
    if (this !is PsiWhiteSpace) return false
    return this.textContains('\n')
  }
}
