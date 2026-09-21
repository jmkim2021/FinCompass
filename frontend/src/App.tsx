import { AdminApp } from './AdminApp'
import { ConsumerApp } from './consumer/ConsumerApp'
import { useEffect, useState } from 'react'

export default function App(){const [admin,setAdmin]=useState(window.location.hash.startsWith('#/admin'));useEffect(()=>{const update=()=>setAdmin(window.location.hash.startsWith('#/admin'));window.addEventListener('hashchange',update);return()=>window.removeEventListener('hashchange',update)},[]);return admin?<AdminApp/>:<ConsumerApp/>}
