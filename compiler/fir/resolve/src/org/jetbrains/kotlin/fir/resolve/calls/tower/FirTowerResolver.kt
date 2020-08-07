/*
 * Copyright 2010-2020 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.fir.resolve.calls.tower

import org.jetbrains.kotlin.fir.resolve.BodyResolveComponents
import org.jetbrains.kotlin.fir.resolve.calls.*
import org.jetbrains.kotlin.fir.resolve.scope
import org.jetbrains.kotlin.fir.typeContext
import org.jetbrains.kotlin.fir.types.ConeClassLikeType
import org.jetbrains.kotlin.resolve.calls.tasks.ExplicitReceiverKind
import org.jetbrains.kotlin.types.AbstractTypeChecker

class FirTowerResolver(
    private val components: BodyResolveComponents,
    resolutionStageRunner: ResolutionStageRunner,
) {
    private val collector = CandidateCollector(components, resolutionStageRunner)
    private val manager = TowerResolveManager(collector)

    fun runResolver(
        info: CallInfo,
        collector: CandidateCollector = this.collector,
        manager: TowerResolveManager = this.manager
    ): CandidateCollector {
        val candidateFactoriesAndCollectors = buildCandidateFactoriesAndCollectors(info, collector)

        val towerResolverSession = FirTowerResolverSession(components, manager, candidateFactoriesAndCollectors, info)
        towerResolverSession.runResolution(info)

        manager.runTasks()
        return collector
    }

    fun runResolverForDelegatingConstructor(
        info: CallInfo,
        constructedType: ConeClassLikeType
    ): CandidateCollector {
        val outerType = components.outerClassManager.outerType(constructedType)
        val scope = constructedType.scope(components.session, components.scopeSession) ?: return collector

        val dispatchReceiver =
            if (outerType != null)
                components.implicitReceiverStack.receiversAsReversed().drop(1).firstOrNull {
                    AbstractTypeChecker.isSubtypeOf(components.session.typeContext, it.type, outerType)
                } ?: return collector // TODO: report diagnostic about not-found receiver
            else
                null

        val candidateFactory = CandidateFactory(components, info)
        val resultCollector = collector

        scope.processDeclaredConstructors {
            resultCollector.consumeCandidate(
                TowerGroup.Member,
                candidateFactory.createCandidate(
                    it,
                    ExplicitReceiverKind.NO_EXPLICIT_RECEIVER,
                    dispatchReceiver,
                    implicitExtensionReceiverValue = null,
                    builtInExtensionFunctionReceiverValue = null
                )
            )
        }

        return collector
    }

    private fun buildCandidateFactoriesAndCollectors(
        info: CallInfo,
        collector: CandidateCollector
    ): CandidateFactoriesAndCollectors {
        val candidateFactory = CandidateFactory(components, info)
        val stubReceiverCandidateFactory =
            if (info.callKind == CallKind.CallableReference && info.stubReceiver != null)
                candidateFactory.replaceCallInfo(info.replaceExplicitReceiver(info.stubReceiver))
            else
                null

        return CandidateFactoriesAndCollectors(
            candidateFactory,
            collector,
            stubReceiverCandidateFactory
        )
    }

    fun reset() {
        collector.newDataSet()
        manager.reset()
    }
}
