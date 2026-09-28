import { useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { FolderSearch } from 'lucide-react'
import { fetchInvestigations, openInvestigation } from '../api/client'
import { useToast } from './Toast'
import { Button } from './ui'

/** Goes to the item's open investigation, or opens one (which links any sightings already reported). */
export default function InvestigateButton({ item, size = 'sm', className, onUnauthorized }) {
  const navigate = useNavigate()
  const toast = useToast()
  const [busy, setBusy] = useState(false)

  const go = async () => {
    setBusy(true)
    try {
      const existing = (await fetchInvestigations({ analysisId: item.id })).find((c) => c.status === 'OPEN')
      const target = existing || (await openInvestigation(item.id))
      if (!existing) toast(`${target.caseNumber} opened for #${item.id}.`)
      navigate(`/investigations/${target.caseNumber}`)
    } catch (err) {
      if ((err.status === 401 || err.status === 403) && onUnauthorized) return onUnauthorized()
      toast(err.message || 'Could not open the investigation', { tone: 'error' })
    } finally {
      setBusy(false)
    }
  }

  return (
    <Button
      variant="ghost"
      size={size}
      icon={FolderSearch}
      loading={busy}
      onClick={go}
      title="Where else has this appeared? Opens the item's investigation, or starts one."
      className={className}
    >
      Investigate
    </Button>
  )
}
