import { HelpCircle, Sparkles, Waves } from 'lucide-react'

/** Generation-family attribution from the Phase 17 family head. */
export const FAMILY = {
  gan: {
    label: 'GAN-era',
    icon: Waves,
    tone: 'border-violet-400/30 bg-violet-400/10 text-violet-200',
    description: 'Looks like a classic face swap / reenactment (FaceForensics++-style generator artefacts).',
  },
  diffusion: {
    label: 'Diffusion',
    icon: Sparkles,
    tone: 'border-cyan-400/30 bg-cyan-400/10 text-cyan-200',
    description: 'Looks like a diffusion model regenerated part of the image (inpainting / img2img).',
  },
  unknown: {
    label: 'Family unknown',
    icon: HelpCircle,
    tone: 'border-line bg-white/[0.03] text-muted',
    description: 'Not judged forged, or the attribution head was not confident enough to say.',
  },
}
