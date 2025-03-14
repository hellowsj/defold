// Copyright 2020-2024 The Defold Foundation
// Copyright 2014-2020 King
// Copyright 2009-2014 Ragnar Svensson, Christian Murray
// Licensed under the Defold License version 1.0 (the "License"); you may not use
// this file except in compliance with the License.
// 
// You may obtain a copy of the License, together with FAQs at
// https://www.defold.com/license
// 
// Unless required by applicable law or agreed to in writing, software distributed
// under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR
// CONDITIONS OF ANY KIND, either express or implied. See the License for the
// specific language governing permissions and limitations under the License.

#ifndef DM_GAMESYS_COMP_PARTICLEFX_H
#define DM_GAMESYS_COMP_PARTICLEFX_H

#include <gameobject/component.h>

namespace dmGameSystem
{
    dmGameObject::CreateResult CompParticleFXNewWorld(const dmGameObject::ComponentNewWorldParams& params);

    dmGameObject::CreateResult CompParticleFXDeleteWorld(const dmGameObject::ComponentDeleteWorldParams& params);

    dmGameObject::CreateResult CompParticleFXCreate(const dmGameObject::ComponentCreateParams& params);

    dmGameObject::CreateResult CompParticleFXDestroy(const dmGameObject::ComponentDestroyParams& params);

    dmGameObject::CreateResult CompParticleFXAddToUpdate(const dmGameObject::ComponentAddToUpdateParams& params);

    dmGameObject::UpdateResult CompParticleFXUpdate(const dmGameObject::ComponentsUpdateParams& params, dmGameObject::ComponentsUpdateResult& update_result);

    dmGameObject::UpdateResult CompParticleFXRender(const dmGameObject::ComponentsRenderParams& params);

    dmGameObject::UpdateResult CompParticleFXOnMessage(const dmGameObject::ComponentOnMessageParams& params);

    void CompParticleFXOnReload(const dmGameObject::ComponentOnReloadParams& params);
}

#endif // DM_GAMESYS_COMP_PARTICLEFX_H
