/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.sql.catalyst.expressions

import org.apache.spark.sql.catalyst.trees
import org.apache.spark.sql.catalyst.errors.attachTree
import org.apache.spark.sql.catalyst.plans.QueryPlan
import org.apache.spark.sql.catalyst.rules.Rule
import org.apache.spark.sql.Logging

/**
 * A bound reference points to a specific slot in the input tuple, allowing the actual value
 * to be retrieved more efficiently.  However, since operations like column pruning can change
 * the layout of intermediate tuples, BindReferences should be run after all such transformations.
 */
case class BoundReference(ordinal: Int, baseReference: Attribute)
  extends Attribute with trees.LeafNode[Expression] {

  type EvaluatedType = Any

  def nullable = baseReference.nullable
  def dataType = baseReference.dataType
  def exprId = baseReference.exprId
  def qualifiers = baseReference.qualifiers
  def name = baseReference.name

  def newInstance = BoundReference(ordinal, baseReference.newInstance)
  def withQualifiers(newQualifiers: Seq[String]) =
    BoundReference(ordinal, baseReference.withQualifiers(newQualifiers))

  override def toString = s"$baseReference:$ordinal"

  override def apply(input: Row): Any = input(ordinal)
}

class BindReferences[TreeNode <: QueryPlan[TreeNode]] extends Rule[TreeNode] {
  import BindReferences._

  def apply(plan: TreeNode): TreeNode = {
    plan.transform {
      case leafNode if leafNode.children.isEmpty => leafNode
      case unaryNode if unaryNode.children.size == 1 => unaryNode.transformExpressions { case e =>
        bindReference(e, unaryNode.children.head.output)
      }
    }
  }
}

object BindReferences extends Logging {
  def bindReference(expression: Expression, input: Seq[Attribute]): Expression = {
    expression.transform { case a: AttributeReference =>
      attachTree(a, "Binding attribute") {
        val ordinal = input.indexWhere(_.exprId == a.exprId)
        if (ordinal == -1) {
          // TODO: This fallback is required because some operators (such as ScriptTransform)
          // produce new attributes that can't be bound.  Likely the right thing to do is remove
          // this rule and require all operators to explicitly bind to the input schema that
          // they specify.
          logger.debug(s"Couldn't find $a in ${input.mkString("[", ",", "]")}")
          a
        } else {
          BoundReference(ordinal, a)
        }
      }
    }
  }
}
